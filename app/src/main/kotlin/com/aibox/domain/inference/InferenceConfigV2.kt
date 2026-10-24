package com.aibox.domain.inference

/**
 * 通用推理配置（v2）。
 *
 * 设计目标：用一个数据类覆盖所有后端（llama.cpp / LiteRT-LM / MLC / MNN）与所有任务类型
 * （LLM / VLM / ASR / TTS / 图生 / Embedding），同时不强求"通用字段覆盖所有可能"。
 *
 * - 通用字段：所有后端都识别（threads / batchSize / maxTokens / contextLength / 量化 / 采样 / 优化开关）。
 * - [frameworkExtras]：框架专属配置，用 `Map<String, Any>` 透传，框架自己解析键值约定。
 *   例如 llama.cpp 用 `"nGpuLayers" -> 0`、`"antiRollback" -> false`；
 *   LiteRT 用 `"preferredDelegate" -> "npu"`、`"maxDequeueBuffer" -> 4`。
 *
 * 不可变 data class，调整参数用 `copy()`。
 */
data class InferenceConfigV2(
    // === 任务 ===
    /** 该配置对应的目标任务类型。后端可据此选择不同的子图 / 子流程。 */
    val taskType: TaskType = TaskType.TEXT_GENERATION,

    // === 通用推理参数 ===
    /** CPU 线程数；null = 让自动调优模块按大核数决定。 */
    val threads: Int? = null,
    /** 批处理大小（prompt 阶段 / 批量推理）。 */
    val batchSize: Int = 1,
    /** 单次生成最大 token 数（仅生成任务用）。 */
    val maxTokens: Int = 512,
    /** 上下文窗口长度（token 数）。 */
    val contextLength: Int = 2048,

    // === 通用量化 ===
    /** KV cache 量化级别；默认 F16 兼容性最好。 */
    val kvQuant: QuantType = QuantType.F16,
    /** 权重量化级别；null = 由模型文件本身决定（如 gguf 已包含量化信息）。 */
    val weightQuant: QuantType? = null,

    // === 通用采样（仅生成任务用） ===
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,

    // === 通用优化开关（所有后端都执行） ===
    val optimizations: OptimizationFlags = OptimizationFlags.DEFAULT,

    // === 框架专属配置（透传，框架自己解析） ===
    val frameworkExtras: Map<String, Any> = emptyMap()
)

/**
 * 量化类型枚举（v2）。
 *
 * 统一覆盖 llama.cpp 的 GGUF 量化族、LiteRT-LM 的 tflite 量化、以及裸浮点格式。
 * 各后端按需识别子集，未识别的量化级别应在 loadModel 时通过 [Outcome.Failure] 报错。
 */
enum class QuantType {
    // === 浮点 ===
    F32, F16, BF16,
    // === GGUF 整数量化（llama.cpp 系） ===
    INT8, Q8_0, Q6_0, Q5_0, Q4_0, Q4_1,
    // === GGUF K-quant ===
    Q3_K, Q4_K, Q5_K, Q6_K, Q8_K,
    // === GGUF I-quant（高压缩） ===
    IQ1_S, IQ2_XXS, IQ2_S, IQ3_S, IQ3_M, IQ4_XS,
    // === TFLite 量化（LiteRT-LM 系） ===
    TFLITE_INT8, TFLITE_FP16, TFLITE_BF16
}

/**
 * 优化开关集合（v2）。
 *
 * 设计原则：
 * - 所有字段默认值 = 当前主流手机上的最优默认（性能 + 兼容性折中）。
 * - 三档预置：[DEFAULT] / [PERFORMANCE] / [POWER_SAVE]，用户在设置页一键切换。
 * - 后端在 [InferenceBackendV2.applyOptimizations] 中按自身能力"尽力执行"：
 *   不支持的字段（如后端无 Flash Attention）静默忽略，不报错。
 *
 * @property preloadModel 启动时即 mmap 权重，而非首次推理时再加载（降低首 token 延迟）。
 * @property warmupAfterLoad 加载后立即跑一次空输入预热，避免首次真实推理的编译 / 预热开销。
 * @property preallocateKV 加载时按 contextLength 一次性分配 KV cache，避免逐 token 增长触发 realloc。
 * @property prebuildSampler 加载时即构建采样器对象，避免首次推理时重建。
 * @property streamFirstTokenImmediately 首 token 一就绪就 push，不等 N 个 token 攒批。
 * @property forceFlashAttention 强制使用 Flash Attention；后端不支持则静默忽略。
 * @property aggressiveGraphOpt 启用激进的算子融合 / 常量折叠（增加编译时间换推理速度）。
 * @property continuousKVLayout 使用连续 KV 内存布局（vs 分块），提速但占内存更整块。
 * @property reuseSampler 同会话内复用 sampler 实例，避免每个 token 重建。
 * @property zeroCopyStream 流式输出走零拷贝路径（如 mmap 出来的 buffer 直接引用）。
 * @property enableGraphCache 缓存编译后的计算图，加速同模型二次加载。
 * @property thermalAwareThrottle 热感知降频：温度过高时主动降低 batch / 线程数。
 * @property idleUnloadTimeout 空闲多久后自动卸载模型（毫秒），0 = 永不卸载。
 * @property lowPowerOnBattery 电池模式下进入低功耗档（降低线程数 / 关闭 GPU）。
 */
data class OptimizationFlags(
    // === TTFT 优化 ===
    val preloadModel: Boolean = true,
    val warmupAfterLoad: Boolean = true,
    val preallocateKV: Boolean = true,
    val prebuildSampler: Boolean = true,
    val streamFirstTokenImmediately: Boolean = true,

    // === TG 优化 ===
    val forceFlashAttention: Boolean = true,
    val aggressiveGraphOpt: Boolean = true,
    val continuousKVLayout: Boolean = true,
    val reuseSampler: Boolean = true,
    val zeroCopyStream: Boolean = true,
    val enableGraphCache: Boolean = true,

    // === 能耗优化 ===
    val thermalAwareThrottle: Boolean = true,
    val idleUnloadTimeout: Long = 10 * 60 * 1000,  // 10 分钟
    val lowPowerOnBattery: Boolean = false         // 默认关，用户可开
) {
    companion object {
        /** 默认档：当前主流手机上的最优折中。 */
        val DEFAULT = OptimizationFlags()

        /** 性能档：禁用热感知降频，跑满；适合短时间高强度推理。 */
        val PERFORMANCE = OptimizationFlags(
            thermalAwareThrottle = false,
            lowPowerOnBattery = false
        )

        /** 省电档：开热感知降频 + 电池模式 + 短 idle 卸载。 */
        val POWER_SAVE = OptimizationFlags(
            thermalAwareThrottle = true,
            lowPowerOnBattery = true,
            idleUnloadTimeout = 3 * 60 * 1000  // 3 分钟
        )
    }
}
