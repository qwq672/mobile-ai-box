// native_bridge.cpp —— JNI 入口，桥接 llama.cpp 与 Java 侧 NativeBridge
//
// 设计要点（内存优化 + 稳定性）：
// 1. 模型用 mmap 加载（llama.cpp 0.4.x 默认即 mmap，已无 use_mmap 开关）
// 2. KV cache 量化通过 context_params.type_k/type_v 设置
// 3. completion 在当前线程同步跑（CpuBackend 已经在 Dispatchers.Default 中调用），
//    不再 detach 新线程 —— 避免跨线程 FindClass 找不到 Kotlin 类的 native crash。
// 4. token 回调路径：缓存 Function1.invoke jmethodID 到全局 ref；
//    每次回调 ExceptionCheck + DeleteLocalRef 返回值，避免 local ref table 溢出。
// 5. 全程加锁保护 g_model/g_ctx，避免 unload 与生成并发 use-after-free。
// 6. 编译期兼容：v0.4.1 用 flash_attn_type + llama_memory_clear。

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <exception>

#include "llama.h"
#include "ggml-backend.h"
#include "memory_pool.h"
#include "bridge.h"

#define TAG "AIBox-Native"

// 双路日志：__android_log_print + 通过 JNI 回调传给 Kotlin LogSystem 写文件。
// g_vm 必须在 native_log 之前声明（native_log 内部用 g_vm attach 线程调到 Kotlin）
static JavaVM* g_vm = nullptr;

// native_log_cb 由 setNativeLogCallback 注入；如果未注册则只走 logcat。
static jobject g_log_cb = nullptr;       // 全局 ref to Kotlin lambda（保留接口供将来扩展）
static jclass    g_log_cls = nullptr;    // Kotlin NativeBridge class for onNativeLog
static jmethodID g_on_native_log_mid = nullptr;

static void native_log(int priority, const char* tag, const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    __android_log_print(priority, tag, "%s", buf);

    // 通过 JNI 回调到 Kotlin NativeBridge.onNativeLog
    if (g_vm) {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
            // 当前线程已 attach
        } else if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        }
        if (env && g_log_cls && g_on_native_log_mid) {
            jstring jtag = env->NewStringUTF(tag);
            jstring jmsg  = env->NewStringUTF(buf);
            env->CallStaticVoidMethod(g_log_cls, g_on_native_log_mid,
                                      (jint)priority, jtag, jmsg);
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (jtag) env->DeleteLocalRef(jtag);
            if (jmsg)  env->DeleteLocalRef(jmsg);
        }
        if (attached) g_vm->DetachCurrentThread();
    }
}

#define LOGI(...) native_log(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) native_log(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------- llama.cpp 0.4.x 兼容层 ----------
#define POCKET_SET_FLASH_ATTN(cp) (cp).flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED
#define POCKET_KV_CLEAR(ctx)      llama_memory_clear(llama_get_memory(ctx), true)

// ---------- 全局状态 ----------
static std::mutex g_mutex;            // 保护 load/unload 与 completion 的串行
static llama_model*     g_model  = nullptr;
static llama_context*   g_ctx    = nullptr;
static const llama_vocab* g_vocab = nullptr;
static std::atomic<bool> g_interrupt{false};
// 卸载进行中：unload 会先置位再抢锁，生成线程拿到锁后看到它就直接退出，
// 否则生成末尾的 g_interrupt.store(false) 会把 unload 的取消请求吃掉。
static std::atomic<bool> g_unloading{false};
static std::atomic<int>  g_ctx_used{0};
// KV cache 中已 eval 的 token 数（用于增量 eval，避免每次都从头重新 tokenize+eval）
static int g_n_past_total = 0;
// 上次 completion 用的完整 prompt（用于检测 prompt 前缀是否变化，变化则重置 KV cache）
static std::string g_last_prompt;

// g_vm 已在文件早期声明（native_log 之前），这里不再重复

// g_cb_cls / g_cb_mid（StringCallback.onValue）在 ensure_cb_cache 中按需初始化。

// 内存池
static MemoryPool g_pool;

// 后端字符串（仅供日志）
static std::string g_backend_str = "cpu";

// 最近一次加载失败的原始原因（C++ 异常文本），供 Kotlin 侧展示到「诊断」里。
// llama.cpp 在 Vulkan 设备初始化失败、KV 量化与 FA 冲突等场景下会 throw，
// 这些异常如果跨过 JNI 边界就会直接 abort（表现为闪退），所以必须就地捕获。
static std::mutex g_err_mutex;
static std::string g_last_error;

static void set_last_error(const std::string& msg) {
    std::lock_guard<std::mutex> lk(g_err_mutex);
    g_last_error = msg;
}

static std::string get_last_error() {
    std::lock_guard<std::mutex> lk(g_err_mutex);
    return g_last_error;
}

// ---------- 后端可用性 ----------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aibox_infra_jni_NativeBridge_vulkanAvailable(JNIEnv*, jclass) {
#if defined(POCKET_VULKAN)
    void* h = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (h) { dlclose(h); return JNI_TRUE; }
#endif
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aibox_infra_jni_NativeBridge_npuAvailable(JNIEnv*, jclass) {
#if defined(POCKET_NPU)
    // ggml-hexagon 直接走 FastRPC 与 CDSP 通信，不依赖 libQnnHtp.so。
    // 只要设备暴露了 FastRPC 节点，就认为 HTP 通道存在；skel 是否齐全由
    // Kotlin 侧 HexagonAssets 再检查一次。
    static const char* kFastRpcNodes[] = {
        "/dev/fastrpc-cdsp",
        "/dev/fastrpc-cdsp-secure",
        "/dev/fastrpc-sdsp",
        "/dev/fastrpc-adsp",
        "/dev/adsprpc-smd",
        "/dev/adsprpc-smd-secure",
    };
    for (const char* node : kFastRpcNodes) {
        if (access(node, F_OK) == 0) return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

/**
 * 探测 NPU 类型(Qualcomm HTP / MediaTek APU / Samsung Exynos NPU / unknown)
 * 返回:
 *  - "qcom_htp":   高通 Hexagon HTP(ggml-hexagon 走 FastRPC)
 *  - "mediatek_apu":  联发科 APU(libneuron_adapter.so)
 *  - "samsung_exynos":  三星 Exynos NPU(libeden_nn.so)
 *  - "unknown":    找到 .so 但不识别类型
 *  - "":           无 NPU 或编译时未启用
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_aibox_infra_jni_NativeBridge_npuType(JNIEnv* env, jclass) {
#if defined(POCKET_NPU)
    // 高通 Hexagon：ggml-hexagon 不需要 QNN 的 libQnnHtp.so，只要 FastRPC 通道在
    if (Java_com_aibox_infra_jni_NativeBridge_npuAvailable(env, nullptr) == JNI_TRUE) {
        return env->NewStringUTF("qcom_htp");
    }
    // 联发科 APU
    void* h3 = dlopen("libneuron_adapter.so", RTLD_NOW | RTLD_LOCAL);
    if (h3) {
        dlclose(h3);
        return env->NewStringUTF("mediatek_apu");
    }
    // 三星 Exynos
    void* h4 = dlopen("libeden_nn.so", RTLD_NOW | RTLD_LOCAL);
    if (h4) {
        dlclose(h4);
        return env->NewStringUTF("samsung_exynos");
    }
    return env->NewStringUTF("unknown");
#else
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aibox_infra_jni_NativeBridge_openclAvailable(JNIEnv*, jclass) {
#if defined(POCKET_OPENCL)
    void* h = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (h) { dlclose(h); return JNI_TRUE; }
#endif
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aibox_infra_jni_NativeBridge_nativeVersion(JNIEnv* env, jclass) {
    std::string v = "llama.cpp=" + std::string(llama_print_system_info()) + " aibox=v261018-1";
    return env->NewStringUTF(v.c_str());
}

/**
 * 设置高通 HTP skel 的搜索路径（ADSP_LIBRARY_PATH）。
 *
 * ggml-hexagon 的 FastRPC 层通过该环境变量在 DSP 侧查找
 * `libggml-htp-vXX.so`；必须在 llama_backend_init 之前调用。
 * 只有 AIBOX_NPU 构建才会真正用到，普通构建调用也无副作用。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_setAdspLibraryPath(JNIEnv* env, jclass, jstring jdir) {
    if (!jdir) return;
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    if (!dir) return;
    setenv("ADSP_LIBRARY_PATH", dir, 1);
    LOGI("setAdspLibraryPath: %s", dir);
    env->ReleaseStringUTFChars(jdir, dir);
}

// ---------- 架构检测 ----------
// 读取 GGUF 元数据 general.architecture,返回小写字符串
// llama.cpp v0.4.1 暴露 llama_model_meta_val_str(model, key, buf, size)
// 如果未导出,降级用文件名启发式(路径包含 mamba/rwkv 等关键词)
static std::string detect_model_arch(const llama_model* model) {
    if (!model) return "unknown";
    // 尝试用 llama_model_meta_val_str 读 general.architecture
    // 不同 llama.cpp 版本可能 API 名略不同,这里试两个常见名
    char buf[64] = {0};
    int n = -1;
    // v0.4.1: llama_model_meta_val_str(model, key, buf, size) → 字节数
    n = llama_model_meta_val_str(model, "general.architecture", buf, sizeof(buf));
    if (n > 0) {
        std::string s(buf, (size_t)n);
        // 转小写
        for (auto& c : s) c = (char)tolower((unsigned char)c);
        return s;
    }
    return "unknown";
}

// 最近一次加载/解码失败的原因（空字符串表示没有错误）。
// 之前这类错误只打 logcat，用户只看到「闪退」；现在可以直接显示在设置页的诊断里。
extern "C" JNIEXPORT jstring JNICALL
Java_com_aibox_infra_jni_NativeBridge_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(get_last_error().c_str());
}

// ---------- 加载 ----------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aibox_infra_jni_NativeBridge_llamaLoad(
    JNIEnv* env, jclass, jstring jpath, jstring jbackend,
    jint nGpuLayers, jint threads,
    jint physicalBatch, jint batch,
    jint ctxLen, jstring jkvq,
    jboolean jforceFa)
{
    if (!jpath || !jbackend || !jkvq) {
        LOGE("llamaLoad: null string argument");
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* backend = env->GetStringUTFChars(jbackend, nullptr);
    const char* kvq = env->GetStringUTFChars(jkvq, nullptr);
    if (!path || !backend || !kvq) {
        if (path)    env->ReleaseStringUTFChars(jpath, path);
        if (backend) env->ReleaseStringUTFChars(jbackend, backend);
        if (kvq)     env->ReleaseStringUTFChars(jkvq, kvq);
        LOGE("llamaLoad: GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    const bool forceFa = (jforceFa == JNI_TRUE);
    g_backend_str = backend;

    std::lock_guard<std::mutex> lk(g_mutex);
    bool ok = false;

    // 检测是否为 1-bit / ternary 模型（Bonsai 等），日志提示
    std::string path_str(path);
    bool is_1bit = path_str.find("bonsai") != std::string::npos ||
                   path_str.find("q1_0") != std::string::npos ||
                   path_str.find("ternary") != std::string::npos;
    if (is_1bit) {
        LOGI("llamaLoad: detected 1-bit/ternary model, GPU backend strongly recommended");
    }

    // 上一次 unload / 生成被中断后，这两个标志可能还留着，会让新加载后的第一次
    // 生成直接走 "unloading in progress, abort" 分支（表现为加载完立刻结束/卡住）。
    g_interrupt.store(false);
    g_unloading.store(false);
    g_n_past_total = 0;
    g_last_prompt.clear();

    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_ctx_used.store(0);

    llama_backend_init();
    set_last_error("");

    // ---- 参数合法性校正 ----
    // llama.cpp 对非法参数是 throw / GGML_ABORT，而不是返回错误码；跨过 JNI 就会 abort，
    // 在用户看来就是「闪退」。所以这里先钳制到合法区间。
    const int ctx_len = ctxLen < 256 ? 256 : ctxLen;
    int n_batch  = batch > 0 ? batch : 2048;
    int n_ubatch = physicalBatch > 0 ? physicalBatch : 512;
    if (n_ubatch > n_batch) {
        LOGI("llamaLoad: n_ubatch(%d) > n_batch(%d), clamp to n_batch", n_ubatch, n_batch);
        n_ubatch = n_batch;
    }
    if (n_batch > ctx_len) {
        LOGI("llamaLoad: n_batch(%d) > n_ctx(%d), clamp to n_ctx", n_batch, ctx_len);
        n_batch = ctx_len;
        if (n_ubatch > n_batch) n_ubatch = n_batch;
    }

    // 该 .so 里到底有没有可用的 GPU 后端（Vulkan/OpenCL 没编进来时 ggml 会静默忽略层数，
    // 但某些驱动初始化失败会直接抛异常）
    int n_gpu_layers = nGpuLayers;
    if (n_gpu_layers > 0 && !llama_supports_gpu_offload()) {
        LOGI("llamaLoad: backend=%s 但本库未注册 GPU 后端，n_gpu_layers 置 0", backend);
        n_gpu_layers = 0;
    }
    // 1-bit / ternary 量化（Q1_0 等）在 Vulkan 上缺少完整的内核覆盖，
    // 设备驱动会在 prompt eval 阶段直接 SIGSEGV（日志里连异常都没有）。
    // 这类模型强制走 CPU，稳定优先。
    if (is_1bit && n_gpu_layers > 0) {
        LOGI("llamaLoad: 1-bit/ternary 模型在 GPU 上易触发驱动崩溃，n_gpu_layers 置 0（走 CPU）");
        n_gpu_layers = 0;
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;

    // Hexagon 和 Vulkan 在 ggml 里都注册为 GPU 类型。如果不显式指定设备，
    // llama.cpp 会把所有 GPU 设备都纳入默认列表，模型可能被拆到 NPU 上
    // （或反过来选错设备）。按用户选择的后端强制只用一个设备。
    static ggml_backend_dev_t s_selected[2] = { nullptr, nullptr };
    s_selected[0] = nullptr;
    const char* want_dev = nullptr;
    if (g_backend_str == "npu")         want_dev = "HTP";
    else if (g_backend_str == "vulkan") want_dev = "Vulkan";
    else if (g_backend_str == "opencl") want_dev = "OpenCL";

    if (want_dev != nullptr) {
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            if (d == nullptr) continue;
            ggml_backend_reg_t r = ggml_backend_dev_backend_reg(d);
            const char* reg_name = r ? ggml_backend_reg_name(r) : nullptr;
            const char* dev_name = ggml_backend_dev_name(d);
            if ((reg_name && strstr(reg_name, want_dev)) ||
                (dev_name && strstr(dev_name, want_dev))) {
                s_selected[0] = d;
                break;
            }
        }
        if (s_selected[0] != nullptr) {
            mp.devices = s_selected;
            LOGI("llamaLoad: backend=%s -> device %s (%s)", backend,
                 ggml_backend_dev_name(s_selected[0]),
                 ggml_backend_dev_description(s_selected[0]));
        } else {
            LOGI("llamaLoad: backend=%s 没有匹配的 %s 设备，使用默认设备列表",
                 backend, want_dev);
        }
    }

    try {
        g_model = llama_model_load_from_file(path, mp);
    } catch (const std::exception& e) {
        // Vulkan/Metal 设备初始化失败时走这里，之前会直接 abort 掉进程
        set_last_error(std::string("model_load: ") + e.what());
        LOGE("llama_model_load_from_file threw: %s", e.what());
        g_model = nullptr;
    } catch (...) {
        set_last_error("model_load: unknown exception");
        LOGE("llama_model_load_from_file threw unknown exception");
        g_model = nullptr;
    }
    if (!g_model) {
        LOGE("llama_model_load_from_file failed: %s", path);
        // 不能用 goto(后面有 std::string 局部变量,跨 scope 跳转会编译失败)
        env->ReleaseStringUTFChars(jpath, path);
        env->ReleaseStringUTFChars(jbackend, backend);
        env->ReleaseStringUTFChars(jkvq, kvq);
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);

    // ============ 架构检测 ============
    // 检测 Mamba/RWKV/SSM 等非 Transformer 架构
    // 这些架构与 Vulkan 后端不兼容(已知 bug),与 FlashAttention 也不兼容
    // 检测到后:
    //   - 若用户请求 Vulkan/OpenCL,自动降级到 CPU
    //   - 强制 forceFa = false(覆盖调用方)
    std::string model_arch = detect_model_arch(g_model);
    // 如果读不到 metadata,降级用文件名启发式
    if (model_arch == "unknown") {
        std::string low_path = path_str;
        for (auto& c : low_path) c = (char)tolower((unsigned char)c);
        if (low_path.find("mamba") != std::string::npos)       model_arch = "mamba";
        else if (low_path.find("rwkv") != std::string::npos)   model_arch = "rwkv";
        else if (low_path.find("lfm") != std::string::npos)    model_arch = "mamba";   // LFM2.5 是 Mamba
        else if (low_path.find("jamba") != std::string::npos)  model_arch = "mamba";
        LOGI("llamaLoad: metadata arch unknown, fallback to filename heuristic: '%s'", model_arch.c_str());
    }
    bool is_non_transformer = (model_arch == "mamba" ||
                                model_arch == "rwkv6" ||
                                model_arch == "rwkv" ||
                                model_arch == "ssm" ||
                                model_arch == "jamba" ||
                                model_arch == "gemma2" /* gemma2 也偶有 FA 兼容问题 */);
    if (is_non_transformer) {
        LOGI("llamaLoad: detected non-transformer arch '%s', FA forced off", model_arch.c_str());
    }
    bool effectiveFa = is_non_transformer ? false : forceFa;

    // 若架构是 Mamba/RWKV/SSM 且 backend 是 vulkan/opencl,强制改为 cpu
    // (在 native 层修改 g_backend_str + 后续逻辑用 cpu 路径)
    bool need_fallback_to_cpu = is_non_transformer &&
                                 (std::string(backend) == "vulkan" ||
                                  std::string(backend) == "opencl");
    if (need_fallback_to_cpu) {
        LOGI("llamaLoad: arch '%s' incompatible with %s backend, fallback to cpu",
             model_arch.c_str(), backend);
        g_backend_str = "cpu";
    } else {
        g_backend_str = backend;
    }
    bridge_set_vocab(g_vocab);

    {
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx        = ctx_len;
        cp.n_batch      = n_batch;
        cp.n_ubatch     = n_ubatch;
        cp.n_threads    = threads > 0 ? threads : 4;
        cp.n_threads_batch = threads > 0 ? threads : 4;
        // 关键修复: 只在 effectiveFa=true 时启用 FlashAttention
        // LFM2.5 / Mamba / RWKV 等非 Transformer 架构不兼容 FA, 强制开会导致输出问号
        // effectiveFa 在前面由 detect_model_arch + 调用方 forceFa 综合得出
        if (effectiveFa) {
            POCKET_SET_FLASH_ATTN(cp);
        }
        cp.no_perf      = false;

        std::string kvq_str(kvq);
        // llama.cpp v1.0.0 起：量化 V cache 必须先开 FlashAttention，否则
        // llama-context.cpp 直接 throw std::runtime_error → 跨 JNI abort（闪退）。
        // 非 Transformer 架构又不能开 FA，所以这里降级成 f16 而不是硬开 FA。
        if (kvq_str != "f16" && kvq_str != "fp16" && !effectiveFa) {
            LOGI("llamaLoad: kv=%s 需要 FlashAttention，本模型未启用 FA → 降级为 f16",
                 kvq_str.c_str());
            kvq_str = "f16";
        }
        if (kvq_str == "q8_0")       cp.type_k = cp.type_v = GGML_TYPE_Q8_0;
        else if (kvq_str == "q4_0")  cp.type_k = cp.type_v = GGML_TYPE_Q4_0;
        else                         cp.type_k = cp.type_v = GGML_TYPE_F16;

        try {
            g_ctx = llama_init_from_model(g_model, cp);
        } catch (const std::exception& e) {
            set_last_error(std::string("context_init: ") + e.what());
            LOGE("llama_init_from_model threw: %s", e.what());
            g_ctx = nullptr;
        } catch (...) {
            set_last_error("context_init: unknown exception");
            LOGE("llama_init_from_model threw unknown exception");
            g_ctx = nullptr;
        }
    }
    if (!g_ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model); g_model = nullptr;
        g_vocab = nullptr; bridge_set_vocab(nullptr);
        goto fail;
    }

    g_pool.warmup(ctx_len, n_ubatch, n_batch, std::string(kvq));


    ok = true;
    LOGI("loaded via %s, ctx=%d, threads=%d, kv=%s", g_backend_str.c_str(), ctxLen, threads, kvq);

fail:
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jbackend, backend);
    env->ReleaseStringUTFChars(jkvq, kvq);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_llamaUnload(JNIEnv*, jclass) {
    // 先置中断标志，再抢锁：否则生成线程会一直持有 g_mutex 跑到 EOG，
    // 切模型时主线程卡住 → ANR（用户同样感知为「闪退」）。
    g_unloading.store(true);
    g_interrupt.store(true);
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    bridge_set_vocab(nullptr);
    g_ctx_used.store(0);
    g_n_past_total = 0;
    g_last_prompt.clear();
    g_pool.releaseAll();
    g_unloading.store(false);
    LOGI("unloaded");
}

extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_llamaSetSampler(
    JNIEnv*, jclass, jfloat temp, jint topK, jfloat topP, jfloat repeat)
{
    bridge_sampler_t s{ temp, topK, topP, repeat };
    bridge_set_sampler(s);
}

// ---------- 回调辅助 ----------
// 用 Java interface StringCallback（无 generic）替代 Kotlin Function1，
// 因为某些 NDK/ART 版本下 GetMethodID 在 Kotlin Function1.invoke 上失败。
// StringCallback.onValue(String) 的签名固定：'(Ljava/lang/String;)V'
static jclass    g_cb_cls       = nullptr;  // 全局 ref to com.aibox.infra.jni.StringCallback
static jmethodID g_cb_mid       = nullptr;  // StringCallback.onValue methodID

static bool ensure_cb_cache(JNIEnv* env) {
    if (g_cb_cls && g_cb_mid) return true;
    if (!env) return false;
    if (!g_cb_cls) {
        jclass local = env->FindClass("com/aibox/infra/jni/StringCallback");
        if (!local || env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("ensure_cb_cache: FindClass(StringCallback) failed");
            return false;
        }
        g_cb_cls = (jclass) env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
    }
    if (!g_cb_mid) {
        g_cb_mid = env->GetMethodID(g_cb_cls, "onValue", "(Ljava/lang/String;)V");
        if (!g_cb_mid || env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("ensure_cb_cache: GetMethodID(onValue) failed");
            return false;
        }
    }
    return true;
}

// 安全回调：返回 true 表示成功，false 表示出现 pending exception 或未初始化
static bool call_string_cb(JNIEnv* env, jobject cb, const std::string& s) {
    if (!env || !cb) return false;
    if (!ensure_cb_cache(env)) return false;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jstring js = env->NewStringUTF(s.c_str());
    env->CallVoidMethod(cb, g_cb_mid, js);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (js) env->DeleteLocalRef(js);
        return false;
    }
    if (js) env->DeleteLocalRef(js);
    return true;
}

// ---------- 流式补全（同步执行，由 Kotlin 协程在 Dispatchers.Default 调用） ----------
extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_llamaCompletion(
    JNIEnv* env, jclass, jstring jprompt,
    jobject tokenCb, jobject stopCb)
{
    if (!g_ctx || !g_model || !g_vocab) {
        LOGE("llamaCompletion: no_model");
        call_string_cb(env, stopCb, "no_model");
        return;
    }
    if (!jprompt) {
        LOGE("llamaCompletion: empty_prompt");
        call_string_cb(env, stopCb, "empty_prompt");
        return;
    }

    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt) {
        LOGE("llamaCompletion: GetStringUTFChars failed");
        call_string_cb(env, stopCb, "oom");
        return;
    }
    std::string sp(prompt);
    env->ReleaseStringUTFChars(jprompt, prompt);

    // 锁住全局状态，避免 unload 与生成并发（use-after-free → SIGSEGV）
    std::lock_guard<std::mutex> lk(g_mutex);
    // 上面的空指针检查是无锁的快速失败；拿到锁后必须再检查一次，
    // 因为 close() 可能刚好在两次检查之间把 g_ctx 释放掉。
    if (!g_ctx || !g_model || !g_vocab) {
        LOGE("llamaCompletion: model unloaded during start");
        call_string_cb(env, stopCb, "no_model");
        return;
    }
    if (g_unloading.load()) {
        LOGE("llamaCompletion: unloading in progress, abort");
        call_string_cb(env, stopCb, "no_model");
        return;
    }
    LOGI("llamaCompletion: prompt_len=%zu  ctx=%p  n_past_total=%d  last_len=%zu",
         sp.size(), (void*)g_ctx, g_n_past_total, g_last_prompt.size());

    g_interrupt.store(false);


    // 判断是否需要清空 KV cache 重头 eval：
    // 1. 当前 prompt 不是上次 prompt 的前缀扩展（即前缀变化或缩短）
    // 2. 当前 prompt 为空
    // 否则增量 eval：跳过前 g_n_past_total 个 token，只 eval 新增部分。
    bool need_clear = false;
    if (sp.empty()) {
        need_clear = true;
        LOGI("llamaCompletion: empty prompt, clear KV");
    } else if (g_n_past_total == 0) {
        need_clear = false;  // 第一次，无需 clear
    } else if (sp.size() < g_last_prompt.size() ||
               sp.compare(0, g_last_prompt.size(), g_last_prompt) != 0) {
        // 前缀不匹配（比如用户改了 system prompt 或清空了对话），重置
        need_clear = true;
        LOGI("llamaCompletion: prompt prefix changed, clear KV (last_len=%zu new_len=%zu)",
             g_last_prompt.size(), sp.size());
    } else {
        LOGI("llamaCompletion: incremental eval (skip %d tokens, eval %zu new tokens)",
             g_n_past_total, sp.size() - g_last_prompt.size());
    }

    if (need_clear) {
        llama_memory_t mem = llama_get_memory(g_ctx);
        if (mem) llama_memory_clear(mem, true);
        g_n_past_total = 0;
        g_ctx_used.store(0);
    }
    g_last_prompt = sp;

    // tokenize 整个 prompt
    std::vector<llama_token> tokens;
    {
        int32_t n = llama_tokenize(g_vocab, sp.c_str(), (int32_t)sp.size(),
                                   nullptr, 0, true, true);
        if (n < 0) n = -n;
        tokens.resize((size_t)n);
        int32_t actual = llama_tokenize(g_vocab, sp.c_str(), (int32_t)sp.size(),
                                        tokens.data(), n, true, true);
        if (actual < 0) actual = -actual;
        tokens.resize((size_t)actual);
    }
    LOGI("llamaCompletion: total_tokens=%zu  skip=%d  eval=%zu",
         tokens.size(), g_n_past_total, (size_t)(tokens.size() > (size_t)g_n_past_total ? tokens.size() - g_n_past_total : 0));
    if (tokens.empty()) {
        call_string_cb(env, stopCb, "empty_tokens");
        return;
    }

    const int maxCtx = (int)llama_n_ctx(g_ctx);
    if ((int)tokens.size() > maxCtx - 4) {
        tokens.erase(tokens.begin(), tokens.end() - (maxCtx - 4));
        // 截断后 KV cache 中的 token 和新 prompt 不再匹配，必须清空
        llama_memory_t mem = llama_get_memory(g_ctx);
        if (mem) llama_memory_clear(mem, true);
        g_n_past_total = 0;
        g_ctx_used.store(0);
        LOGI("llamaCompletion: prompt truncated, KV cleared");
    }

    std::string stop_reason = "stop";
    bool stopped = false;
    int n_past = g_n_past_total;  // 从上次结束的位置开始

    // 1. prompt 阶段：只 eval 新增的 token（跳过前 g_n_past_total 个）
    LOGI("llamaCompletion: prompt eval begin, skip %d tokens", g_n_past_total);
    for (size_t i = (size_t)g_n_past_total; i < tokens.size(); i += 256) {
        if (g_interrupt.load()) { stop_reason = "interrupted"; stopped = true; break; }
        int n = std::min((int)256, (int)(tokens.size() - i));
        llama_batch batch = llama_batch_get_one(&tokens[i], n);
        int rc = -1;
        try {
            rc = llama_decode(g_ctx, batch);
        } catch (const std::exception& e) {
            // Vulkan 设备/显存异常会以 C++ 异常形式抛出，跨 JNI 就是 abort（闪退）
            set_last_error(std::string("decode: ") + e.what());
            LOGE("llamaCompletion: prompt decode threw: %s", e.what());
        } catch (...) {
            set_last_error("decode: unknown exception");
            LOGE("llamaCompletion: prompt decode threw unknown exception");
        }
        if (rc != 0) {
            LOGE("llamaCompletion: prompt decode failed rc=%d at i=%zu", rc, i);
            stop_reason = "decode_failed"; stopped = true; break;
        }
        n_past += n;
        g_ctx_used.store(n_past);
    }
    g_n_past_total = n_past;
    LOGI("llamaCompletion: prompt eval done, n_past=%d", n_past);

    // 2. generation 阶段
    if (!stopped) {
        llama_sampler* s = bridge_build_sampler();
        char piece_buf[256];
        int gen_count = 0;
        LOGI("llamaCompletion: generation begin (max 2048 tokens)");
        for (int i = 0; i < 2048; i++) {
            if (g_interrupt.load()) { stop_reason = "interrupted"; break; }
            llama_token id = llama_sampler_sample(s, g_ctx, -1);
            if (llama_vocab_is_eog(g_vocab, id)) { stop_reason = "stop"; LOGI("llamaCompletion: EOG at i=%d", i); break; }

            int r = llama_token_to_piece(g_vocab, id, piece_buf, sizeof(piece_buf), 0, true);
            std::string piece(r > 0 ? piece_buf : "", r > 0 ? (size_t)r : 0);
            if (!call_string_cb(env, tokenCb, piece)) {
                LOGE("llamaCompletion: token callback failed at i=%d", i);
                stop_reason = "callback_error"; break;
            }
            gen_count++;

            llama_batch batch = llama_batch_get_one(&id, 1);
            int rc = 0;
            try {
                rc = llama_decode(g_ctx, batch);
            } catch (const std::exception& e) {
                set_last_error(std::string("decode: ") + e.what());
                LOGE("llamaCompletion: gen decode threw: %s", e.what());
                rc = -1;
            } catch (...) {
                set_last_error("decode: unknown exception");
                LOGE("llamaCompletion: gen decode threw unknown exception");
                rc = -1;
            }
            if (rc != 0) {
                LOGE("llamaCompletion: gen decode failed at i=%d", i);
                stop_reason = "decode_failed"; break;
            }
            n_past++;
            g_n_past_total = n_past;
            g_ctx_used.store(n_past);
            if (n_past >= maxCtx - 1) { stop_reason = "length"; break; }
        }
        LOGI("llamaCompletion: generation done, generated=%d reason=%s", gen_count, stop_reason.c_str());
        llama_sampler_free(s);
    }

    // 最终回调 stop
    LOGI("llamaCompletion: calling stop_cb reason=%s", stop_reason.c_str());
    call_string_cb(env, stopCb, stop_reason);
}

// ---------- chat template 版补全 ----------
// 把 messages 数组用模型内置 chat template 格式化，然后走相同的增量 eval + 生成流程
extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_llamaCompletionChat(
    JNIEnv* env, jclass,
    jobjectArray jMessages, jboolean jThinkingMode,
    jobject tokenCb, jobject stopCb)
{
    if (!g_ctx || !g_model || !g_vocab) {
        LOGE("llamaCompletionChat: no_model");
        call_string_cb(env, stopCb, "no_model");
        return;
    }
    if (!jMessages) {
        call_string_cb(env, stopCb, "empty_messages");
        return;
    }

    // 1. 把 jMessages (Array<ChatMsg>) 拆成 std::vector<llama_chat_message>
    jsize n_msg = env->GetArrayLength(jMessages);
    if (n_msg <= 0) {
        call_string_cb(env, stopCb, "empty_messages");
        return;
    }

    // 找 ChatMsg class 的 role / content 字段
    jclass msgCls = env->FindClass("com/aibox/infra/jni/NativeBridge$ChatMsg");
    if (!msgCls || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("llamaCompletionChat: cannot find ChatMsg class");
        call_string_cb(env, stopCb, "no_chatmsg_class");
        return;
    }
    jfieldID roleFid = env->GetFieldID(msgCls, "role", "Ljava/lang/String;");
    jfieldID contentFid = env->GetFieldID(msgCls, "content", "Ljava/lang/String;");
    if (!roleFid || !contentFid) {
        env->ExceptionClear();
        LOGE("llamaCompletionChat: cannot find ChatMsg.role/content");
        env->DeleteLocalRef(msgCls);
        call_string_cb(env, stopCb, "no_chatmsg_fields");
        return;
    }

    std::vector<llama_chat_message> chat;
    chat.reserve(n_msg);
    // 持久化 string 内容到 std::string，chat[i].role/content 指向 .c_str()
    std::vector<std::pair<std::string, std::string>> strings;
    strings.reserve(n_msg);

    bool thinkingMode = (jThinkingMode == JNI_TRUE);
    const std::string think_flag = thinkingMode ? "/think" : "/no_think";

    for (jsize i = 0; i < n_msg; i++) {
        jobject msg = env->GetObjectArrayElement(jMessages, i);
        if (!msg) continue;
        jstring jrole = (jstring) env->GetObjectField(msg, roleFid);
        jstring jcontent = (jstring) env->GetObjectField(msg, contentFid);
        std::string role = "user";
        std::string content;
        if (jrole) {
            const char* r = env->GetStringUTFChars(jrole, nullptr);
            if (r) { role = r; env->ReleaseStringUTFChars(jrole, r); }
        }
        if (jcontent) {
            const char* c = env->GetStringUTFChars(jcontent, nullptr);
            if (c) { content = c; env->ReleaseStringUTFChars(jcontent, c); }
        }

        // 不再往 system message 里塞 /think：Qwen3 等模板只识别 user turn 里的标志，
        // 且系统提示词为空时会被整条跳过。统一在下面追加到最后一条 user message。
        strings.emplace_back(std::move(role), std::move(content));
        if (jrole) env->DeleteLocalRef(jrole);
        if (jcontent) env->DeleteLocalRef(jcontent);
        env->DeleteLocalRef(msg);
    }
    env->DeleteLocalRef(msgCls);

    // 把 /think 或 /no_think 追加到最后一条 user message（与 Qwen3 官方用法一致）；
    // 如果整段对话没有 user，则退回追加到最后一条消息。
    if (!strings.empty()) {
        int target = -1;
        for (int i = (int) strings.size() - 1; i >= 0; --i) {
            if (strings[i].first == "user") { target = i; break; }
        }
        if (target < 0) target = (int) strings.size() - 1;
        std::string& c = strings[target].second;
        if (c.find(think_flag) == std::string::npos) {
            if (!c.empty() && c.back() != '\n') c += "\n";
            c += think_flag;
        }
    }

    for (auto& p : strings) {
        chat.push_back({p.first.c_str(), p.second.c_str()});
    }
    LOGI("llamaCompletionChat: n_msg=%zu  thinking=%d", chat.size(), thinkingMode ? 1 : 0);

    // 2. 拿模型内置 chat template
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl) {
        LOGE("llamaCompletionChat: model has no chat template, fallback to llamaCompletion");
        // 拼简化 prompt
        std::string sp;
        for (auto& p : strings) {
            sp += p.first + ": " + p.second + "\n";
        }
        sp += "Assistant: ";
        // 走原有逻辑（用 sp 作为 prompt）
        // 这里简化：直接调 call_string_cb 报错，让 Kotlin 侧用旧路径
        call_string_cb(env, stopCb, "no_chat_template");
        return;
    }

    // 3. 用 llama_chat_apply_template 格式化 prompt
    std::vector<char> buf(8192);
    int32_t needed = llama_chat_apply_template(tmpl, chat.data(), chat.size(),
                                                /*add_ass=*/true,
                                                buf.data(), (int32_t)buf.size());
    if (needed < 0) {
        LOGE("llamaCompletionChat: apply template failed");
        call_string_cb(env, stopCb, "template_failed");
        return;
    }
    if (needed > (int32_t)buf.size()) {
        buf.resize(needed + 1);
        needed = llama_chat_apply_template(tmpl, chat.data(), chat.size(),
                                            true, buf.data(), (int32_t)buf.size());
        if (needed < 0) {
            LOGE("llamaCompletionChat: apply template (retry) failed");
            call_string_cb(env, stopCb, "template_failed");
            return;
        }
    }
    std::string sp(buf.data(), needed);
    LOGI("llamaCompletionChat: formatted prompt_len=%zu", sp.size());

    // 4. 锁 + 增量 eval + 生成（复用 llamaCompletion 逻辑）
    std::lock_guard<std::mutex> lk(g_mutex);
    // 与 llamaCompletion 相同的二次检查：tokenize 期间模型可能已被 close()
    if (!g_ctx || !g_model || !g_vocab) {
        LOGE("llamaCompletionChat: model unloaded during start");
        call_string_cb(env, stopCb, "no_model");
        return;
    }
    if (g_unloading.load()) {
        LOGE("llamaCompletionChat: unloading in progress, abort");
        call_string_cb(env, stopCb, "no_model");
        return;
    }
    g_interrupt.store(false);

    // 增量 eval 判断
    bool need_clear = false;
    if (sp.empty()) {
        need_clear = true;
    } else if (g_n_past_total == 0) {
        need_clear = false;
    } else if (sp.size() < g_last_prompt.size() ||
               sp.compare(0, g_last_prompt.size(), g_last_prompt) != 0) {
        need_clear = true;
        LOGI("llamaCompletionChat: prompt prefix changed, clear KV");
    }
    if (need_clear) {
        llama_memory_t mem = llama_get_memory(g_ctx);
        if (mem) llama_memory_clear(mem, true);
        g_n_past_total = 0;
        g_ctx_used.store(0);
    }
    g_last_prompt = sp;

    // tokenize
    std::vector<llama_token> tokens;
    {
        int32_t n = llama_tokenize(g_vocab, sp.c_str(), (int32_t)sp.size(),
                                   nullptr, 0, true, true);
        if (n < 0) n = -n;
        tokens.resize((size_t)n);
        int32_t actual = llama_tokenize(g_vocab, sp.c_str(), (int32_t)sp.size(),
                                        tokens.data(), n, true, true);
        if (actual < 0) actual = -actual;
        tokens.resize((size_t)actual);
    }
    LOGI("llamaCompletionChat: tokens=%zu skip=%d eval=%zu",
         tokens.size(), g_n_past_total,
         (size_t)(tokens.size() > (size_t)g_n_past_total ? tokens.size() - g_n_past_total : 0));
    if (tokens.empty()) {
        call_string_cb(env, stopCb, "empty_tokens");
        return;
    }

    const int maxCtx = (int)llama_n_ctx(g_ctx);
    if ((int)tokens.size() > maxCtx - 4) {
        tokens.erase(tokens.begin(), tokens.end() - (maxCtx - 4));
        llama_memory_t mem = llama_get_memory(g_ctx);
        if (mem) llama_memory_clear(mem, true);
        g_n_past_total = 0;
        g_ctx_used.store(0);
    }

    std::string stop_reason = "stop";
    bool stopped = false;
    int n_past = g_n_past_total;

    // prompt eval
    for (size_t i = (size_t)g_n_past_total; i < tokens.size(); i += 256) {
        if (g_interrupt.load()) { stop_reason = "interrupted"; stopped = true; break; }
        int n = std::min((int)256, (int)(tokens.size() - i));
        llama_batch batch = llama_batch_get_one(&tokens[i], n);
        int rc = llama_decode(g_ctx, batch);
        if (rc != 0) {
            LOGE("llamaCompletionChat: prompt decode failed rc=%d at i=%zu", rc, i);
            stop_reason = "decode_failed"; stopped = true; break;
        }
        n_past += n;
        g_ctx_used.store(n_past);
    }
    g_n_past_total = n_past;
    LOGI("llamaCompletionChat: prompt eval done, n_past=%d", n_past);

    // generation
    if (!stopped) {
        llama_sampler* s = bridge_build_sampler();
        char piece_buf[256];
        int gen_count = 0;
        for (int i = 0; i < 2048; i++) {
            if (g_interrupt.load()) { stop_reason = "interrupted"; break; }
            llama_token id = llama_sampler_sample(s, g_ctx, -1);
            if (llama_vocab_is_eog(g_vocab, id)) { stop_reason = "stop"; break; }

            int r = llama_token_to_piece(g_vocab, id, piece_buf, sizeof(piece_buf), 0, true);
            std::string piece(r > 0 ? piece_buf : "", r > 0 ? (size_t)r : 0);
            if (!call_string_cb(env, tokenCb, piece)) {
                stop_reason = "callback_error"; break;
            }
            gen_count++;

            llama_batch batch = llama_batch_get_one(&id, 1);
            if (llama_decode(g_ctx, batch) != 0) {
                stop_reason = "decode_failed"; break;
            }
            n_past++;
            g_n_past_total = n_past;
            g_ctx_used.store(n_past);
            if (n_past >= maxCtx - 1) { stop_reason = "length"; break; }
        }
        LOGI("llamaCompletionChat: generation done, generated=%d reason=%s", gen_count, stop_reason.c_str());
        llama_sampler_free(s);
    }

    call_string_cb(env, stopCb, stop_reason);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_llamaInterrupt(JNIEnv*, jclass) {
    g_interrupt.store(true);
}

// ---------- 状态 ----------
extern "C" JNIEXPORT jfloat JNICALL
Java_com_aibox_infra_jni_NativeBridge_llamaTokensPerSecond(JNIEnv*, jclass) {
    if (!g_ctx) return 0.f;
    auto pd = llama_perf_context(g_ctx);
    if (pd.t_eval_ms <= 0.0 || pd.n_eval <= 0) return 0.f;
    return (jfloat)(pd.n_eval / (pd.t_eval_ms / 1000.0));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aibox_infra_jni_NativeBridge_llamaContextUsed(JNIEnv*, jclass) {
    return (jint)g_ctx_used.load();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aibox_infra_jni_NativeBridge_llamaContextMax(JNIEnv*, jclass) {
    if (!g_ctx) return 0;
    return (jint)llama_n_ctx(g_ctx);
}

// ---------- 内存池 ----------
extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_memoryPoolWarmup(JNIEnv*, jclass, jlong kv, jlong comp) {
    g_pool.warmup_bytes(kv, comp);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_memoryPoolRelease(JNIEnv*, jclass) {
    g_pool.releaseAll();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_memoryPoolShrinkIdle(JNIEnv*, jclass, jlong idleMs, jint keep) {
    g_pool.shrink_idle(idleMs, keep);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aibox_infra_jni_NativeBridge_memoryPoolPeakMb(JNIEnv*, jclass) {
    return (jlong)g_pool.peak_mb();
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_aibox_infra_jni_NativeBridge_borrowDirectBuffer(JNIEnv* env, jclass, jint size) {
    return g_pool.borrow_direct(env, size);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_NativeBridge_returnDirectBuffer(JNIEnv* env, jclass, jobject buf) {
    g_pool.return_direct(env, buf);
}

// ---------- 热感知 ----------
extern "C" JNIEXPORT jint JNICALL
Java_com_aibox_infra_jni_NativeBridge_thermalPercent(JNIEnv*, jclass) {
    return (jint)bridge_read_thermal_percent();
}

// ---------- native log callback ----------
// native_log() 直接调 NativeBridge.onNativeLog(level, tag, msg) 静态方法，
// 不再需要 JNI setNativeLogCallback 接口（Kotlin 端通过 setLogHandler 注册 handler）。

// ---------- JNI_OnLoad ----------
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_VERSION_1_6;
    // 在主线程 ClassLoader 上下文预缓存 StringCallback.onValue（后续 completion 回调用）
    // 这里调 ensure_cb_cache 在主线程做 FindClass，避免运行时在 Dispatchers.Default
    // 线程上 FindClass 失败（虽然项目类一般能找到，但保险起见）。
    ensure_cb_cache(env);
    // 缓存 NativeBridge.onNativeLog 静态方法用于 native_log 回调
    jclass local = env->FindClass("com/aibox/infra/jni/NativeBridge");
    if (local && !env->ExceptionCheck()) {
        g_log_cls = (jclass) env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        g_on_native_log_mid = env->GetStaticMethodID(g_log_cls, "onNativeLog",
            "(ILjava/lang/String;Ljava/lang/String;)V");
        if (!g_on_native_log_mid || env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("JNI_OnLoad: cannot find NativeBridge.onNativeLog");
            g_on_native_log_mid = nullptr;
        }
    } else {
        env->ExceptionClear();
        LOGE("JNI_OnLoad: cannot find NativeBridge class");
    }
    LOGI("JNI_OnLoad: cb_cache=%s  log_mid=%p",
         ensure_cb_cache(env) ? "ok" : "FAIL",
         (void*)g_on_native_log_mid);
    return JNI_VERSION_1_6;
}
