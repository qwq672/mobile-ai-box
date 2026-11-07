package com.pocketllm.domain.inference

/**
 * 后端类型枚举。
 *
 * - CPU：兜底，所有设备可用，自动线程数。
 * - VULKAN：GPU 通用计算，兼容性最好。
 * - NPU：ggml-hexagen，高通/联发科 NPU。
 * - OPENCL：旧 GPU fallback。
 */
enum class BackendType(val id: String, val displayNameZh: String, val displayNameEn: String) {
    CPU    ("cpu",     "CPU",          "CPU"),
    VULKAN ("vulkan",  "Vulkan (GPU)", "Vulkan (GPU)"),
    NPU    ("npu",     "NPU (HexaGen)", "NPU (HexaGen)"),
    OPENCL ("opencl",  "OpenCL (GPU)", "OpenCL (GPU)");

    companion object {
        fun fromId(id: String?): BackendType = entries.firstOrNull { it.id == id } ?: CPU
    }
}

/**
 * 推理后端抽象。所有后端实现该接口，由 [InferenceEngine] 统一调度。
 *
 * 设计原则：
 * 1. load() 时一次性申请 KV cache + 计算 buffer，避免逐 token 反复 malloc（手机发烫的真凶之一）。
 * 2. completion() 流式回调 token，外层不持有大对象，减少 GC 压力。
 * 3. unload() 必须真正释放，且把 buffer 还给 [MemoryPool] 复用。
 * 4. 支持运行时切换 [InferenceConfig]（不重新加载模型的情况下调温度/topK 等）。
 */
interface Backend : AutoCloseable {

    /** 后端类型 */
    val type: BackendType

    /** 当前设备是否可用（例如 NPU 在没有 HTP 的手机上返回 false） */
    fun isAvailable(): Boolean

    /**
     * 加载模型 + 预分配内存。
     * @param modelPath gguf 绝对路径
     * @param config 推理配置（含上下文长度、KV 量化、批处理等）
     * @return true 加载成功
     */
    suspend fun load(modelPath: String, config: InferenceConfig): Boolean

    /** 是否已加载 */
    fun isLoaded(): Boolean

    /**
     * 流式补全（简化 prompt 模板）。
     * @param prompt 已含对话模板的字符串
     * @param onToken 每生成一个 token 回调一次（主线程）
     * @param onStopReason "stop" | "length" | "interrupted"
     */
    suspend fun completion(
        prompt: String,
        onToken: (String) -> Unit,
        onStopReason: (String) -> Unit
    )

    /**
     * 流式补全（chat template 版）。
     * @param messages 消息列表，role: "system" / "user" / "assistant"
     * @param thinkingMode 是否开启思考模式（Qwen3 /think 标志）
     * @param onToken 每生成一个 token 回调一次
     * @param onStopReason stop reason
     */
    suspend fun completionChat(
        messages: List<com.pocketllm.infra.jni.NativeBridge.ChatMsg>,
        thinkingMode: Boolean,
        onToken: (String) -> Unit,
        onStopReason: (String) -> Unit
    )

    /** 中断当前生成 */
    fun interrupt()

    /** 释放模型与所有 buffer */
    override fun close()

    /** 实时统计（用于 UI 显示） */
    fun stats(): BackendStats
}

/** 后端实时统计，UI 显示 tokens/s、内存占用、当前温度档等。 */
data class BackendStats(
    val tokensPerSecond: Float,
    val contextUsed: Int,
    val contextMax: Int,
    val peakMemoryMb: Long,
    val thermalPercent: Int,   // 0-100，由 ThermalGovernor 注入
    val backend: BackendType
)
