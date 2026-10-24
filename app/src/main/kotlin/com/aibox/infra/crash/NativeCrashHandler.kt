package com.aibox.infra.crash

/**
 * Native 崩溃捕获的 Kotlin 桥接层。
 *
 * 调用 native 方法安装 SIGSEGV/SIGABRT/SIGFPE/SIGILL/SIGBUS 信号处理器。
 * 信号触发时 native 层会写入崩溃独立文件（async-signal-safe）。
 *
 * 注意:本 object 是单例,external fun 直接放在 object 体内(不需要 companion)。
 * 对应 JNI 函数名: Java_com_aibox_infra_crash_NativeCrashHandler_installNativeCrashHandler
 */
object NativeCrashHandler {

    private var installed = false

    /**
     * 安装 native 信号捕获。
     * @param crashDir 崩溃文件写入目录（应该是 app cache dir，权限放宽）
     */
    fun install(crashDir: String) {
        if (installed) return
        try {
            installNativeCrashHandler(crashDir)
            installed = true
        } catch (e: Throwable) {
            // 失败不致命,Java 端的 CrashHandler 仍生效
        }
    }

    init {
        // CMakeLists.txt: add_library(aibox SHARED ...) 生成 libaibox.so
        System.loadLibrary("aibox")
    }

    @JvmStatic
    external fun installNativeCrashHandler(crashDir: String)
}
