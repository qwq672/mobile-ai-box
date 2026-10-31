// crash_handler.cpp —— Native 信号崩溃捕获
//
// 解决的问题：Java 的 UncaughtExceptionHandler 捕获不到 SIGSEGV/SIGABRT 等 native 信号，
// 用户遇到 native crash 时崩溃日志根本写不到文件，只能 adb logcat 抓取。
//
// 本模块的设计：
// 1. 在 JNI 加载时通过 installNativeCrashHandler 安装 signal handler
// 2. 捕获 SIGSEGV/SIGABRT/SIGFPE/SIGILL/SIGBUS
// 3. signal handler 触发时：
//    a. 用 async-signal-safe 函数写 native 栈回溯到 /data/data/<pkg>/cache/native_crash_<pid>.txt
//    b. 通过 JNI 调用 CrashHandler.onNativeCrash（如果可行，否则跳过）
//    c. 重新触发信号让系统走默认流程
// 4. 文件路径通过 setCrashDir 传入
//
// 重要约束（POSIX signal handler）：
// - 只能用 async-signal-safe 函数（open/write/close/_exit 等）
// - 不能用 malloc、std::string、std::ostringstream、printf 等
// - 不能调用 JNI（不安全）
// - 用栈缓冲 + write 即可

#include <jni.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unwind.h>  // 替代 execinfo.h,Android NDK 26+ 默认未暴露 execinfo

#define TAG "AIBox-Crash"

static char g_crash_dir[512] = {0};
static struct sigaction g_old_handlers[NSIG];

// async-signal-safe 的字符串写入
static void safe_write(int fd, const char* s) {
    if (fd >= 0 && s) {
        size_t len = 0;
        while (s[len]) len++;
        write(fd, s, len);
    }
}

static void safe_write_int(int fd, int v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", v);
    safe_write(fd, buf);
}

static void safe_write_hex(int fd, void* ptr) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%p", ptr);
    safe_write(fd, buf);
}

// ============ unwind callback 必须在函数外定义(C 不支持嵌套函数) ============
struct BacktraceContext {
    int fd;
    int count;
};

static _Unwind_Reason_Code aibox_unwind_callback(struct _Unwind_Context* ctx, void* data) {
    auto* btCtx = static_cast<BacktraceContext*>(data);
    if (btCtx->count >= 64) return _URC_END_OF_STACK;
    void* pc = (void*)_Unwind_GetIP(ctx);
    if (pc) {
        safe_write(btCtx->fd, "  #");
        safe_write_int(btCtx->fd, btCtx->count);
        safe_write(btCtx->fd, " pc ");
        safe_write_hex(btCtx->fd, pc);
        safe_write(btCtx->fd, "\n");
        btCtx->count++;
    }
    return _URC_NO_REASON;
}
// ===========================================================================

// 写崩溃信息到文件（async-signal-safe）
static void write_native_crash(int sig, siginfo_t* info, void* context) {
    if (g_crash_dir[0] == 0) return;

    time_t now = time(NULL);
    struct tm tm_info;
    localtime_r(&now, &tm_info);
    char time_str[32];
    strftime(time_str, sizeof(time_str), "%Y%m%d_%H%M%S", &tm_info);

    char path[640];
    snprintf(path, sizeof(path), "%s/native_crash_%d_%s.txt", g_crash_dir, getpid(), time_str);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return;

    safe_write(fd, "=== AIBox Native Crash Report ===\n");
    safe_write(fd, "Time: "); safe_write(fd, time_str); safe_write(fd, "\n");
    safe_write(fd, "PID: "); safe_write_int(fd, getpid()); safe_write(fd, "\n");
    safe_write(fd, "Signal: "); safe_write_int(fd, sig); safe_write(fd, "\n");

    const char* sig_name = "unknown";
    switch (sig) {
        case SIGSEGV: sig_name = "SIGSEGV"; break;
        case SIGABRT: sig_name = "SIGABRT"; break;
        case SIGFPE:  sig_name = "SIGFPE";  break;
        case SIGILL:  sig_name = "SIGILL";  break;
        case SIGBUS:  sig_name = "SIGBUS";  break;
    }
    safe_write(fd, "Signal Name: "); safe_write(fd, sig_name); safe_write(fd, "\n");
    safe_write(fd, "Code: "); safe_write_int(fd, info ? info->si_code : 0); safe_write(fd, "\n");
    safe_write(fd, "Fault Address: "); safe_write_hex(fd, info ? info->si_addr : NULL); safe_write(fd, "\n");
    safe_write(fd, "\n");

    safe_write(fd, "=== Native Stack Trace (approx) ===\n");
    // Android NDK 26+ 不再默认暴露 execinfo.h,改用 _Unwind_Backtrace (libunwind)
    // 这是 async-signal-safe 的,在 signal handler 中可安全调用
    // aibox_unwind_callback 已在文件顶部定义(不能嵌套在函数内)
    BacktraceContext btCtx = { fd, 0 };
    _Unwind_Backtrace(aibox_unwind_callback, &btCtx);
    if (btCtx.count == 0) {
        safe_write(fd, "(stack trace unavailable)\n");
    }
    safe_write(fd, "\n");

    safe_write(fd, "=== End of Native Crash Report ===\n");
    close(fd);

    // 同时输出到 logcat
    __android_log_print(ANDROID_LOG_FATAL, TAG, "Native crash signal=%d written to %s", sig, path);
}

static void aibox_signal_handler(int sig, siginfo_t* info, void* context) {
    write_native_crash(sig, info, context);

    // 恢复默认处理
    sigaction(sig, &g_old_handlers[sig], NULL);
    raise(sig);
}

// 安装 native 信号 handler
extern "C" {

JNIEXPORT void JNICALL
Java_com_aibox_infra_crash_NativeCrashHandler_installNativeCrashHandler(
    JNIEnv* env, jclass clazz, jstring crashDirJ) {

    const char* crash_dir = env->GetStringUTFChars(crashDirJ, NULL);
    if (crash_dir) {
        strncpy(g_crash_dir, crash_dir, sizeof(g_crash_dir) - 1);
        g_crash_dir[sizeof(g_crash_dir) - 1] = 0;
        // 确保目录存在
        mkdir(g_crash_dir, 0755);
    }
    env->ReleaseStringUTFChars(crashDirJ, crash_dir);

    // 注册感兴趣的信号
    int signals[] = { SIGSEGV, SIGABRT, SIGFPE, SIGILL, SIGBUS };
    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); i++) {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = aibox_signal_handler;
        sa.sa_flags = SA_SIGINFO | SA_RESTART;
        sigemptyset(&sa.sa_mask);
        if (sigaction(signals[i], &sa, &g_old_handlers[signals[i]]) == 0) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "Installed handler for signal %d", signals[i]);
        }
    }
}

}  // extern "C"
