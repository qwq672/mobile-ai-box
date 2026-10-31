package com.aibox.domain.inference

/**
 * 后端能力声明（v2）。
 *
 * 由 [InferenceBackendV2.describeCapabilities] / [BackendDescriptor.describeCapabilities] 返回，
 * 供 [BackendManagerV2] 做"任务 + 模型格式 + 输入输出模态"三维路由。
 *
 * 设计原则：
 * - 所有字段都是"声明式"的，描述后端"能做什么"，而不是"当前在做什么"。
 * - 不可变 data class，运行期不修改；如需动态能力（如 NPU 因降级不可用），
 *   由 BackendManagerV2 通过路由策略选择其它后端实现，而非修改本数据类。
 *
 * @property supportedTasks 该后端支持的任务集合。
 * @property supportedInputModalities 该后端能消费的输入模态集合。
 * @property supportedOutputModalities 该后端能产出的输出模态集合。
 * @property maxContextLength 单次推理最大上下文 token 长度（仅生成 / 理解类任务有意义，其它任务可填 0）。
 * @property maxImageResolution 单张图像最大边长像素；不支持图像输入输出时为 null。
 * @property supportedModelFormats 该后端能加载的模型文件格式，如 ["gguf"] / ["tflite"]。
 * @property supportedQuantizations 该后端支持的量化方式（[QuantType] 名称）。空列表表示"由模型决定"。
 * @property features 该后端支持的硬件加速 / 高级特性集合，见 [BackendFeature]。
 */
data class BackendCapabilities(
    val supportedTasks: Set<TaskType>,
    val supportedInputModalities: Set<Modality>,
    val supportedOutputModalities: Set<Modality>,
    val maxContextLength: Int,
    val maxImageResolution: Int? = null,
    val supportedModelFormats: List<String>,
    val supportedQuantizations: List<String> = emptyList(),
    val features: Set<BackendFeature> = emptySet()
)

/**
 * 后端硬件加速 / 高级特性枚举（v2）。
 *
 * - 硬件加速分支：[FLASH_ATTENTION] / [VULKAN_GPU] / [OPENCL_GPU] / [HEXAGON_NPU] / [TENSOR_GTP] / [MTK_APU]。
 * - 推理高级特性：[STREAMING] / [BATCHED_INFERENCE] / [KV_CACHE_QUANTIZATION] / [MULTIMODAL] /
 *   [FUNCTION_CALLING] / [GUIDED_GENERATION] / [SPECULATIVE_DECODING]。
 *
 * 一个后端可同时声明多个特性；某些特性互斥（如 VULKAN_GPU 与 HEXAGON_NPU），由后端自己保证一致性。
 */
enum class BackendFeature {
    // === 硬件加速 ===
    /** Flash Attention 2 / 3。 */
    FLASH_ATTENTION,
    /** Vulkan GPU 通用计算。 */
    VULKAN_GPU,
    /** OpenCL GPU 通用计算（多用于旧 GPU 兜底）。 */
    OPENCL_GPU,
    /** 高通 Hexagon NPU（QNN / HTP）。 */
    HEXAGON_NPU,
    /** Google Tensor GTP / Edge TPU（Pixel 系列）。 */
    TENSOR_GTP,
    /** 联发科 APU（MTK APU / NeuroPilot）。 */
    MTK_APU,

    // === 推理高级特性 ===
    /** 流式输出（逐 token / 逐帧）。 */
    STREAMING,
    /** 批量推理（同时处理多个 request）。 */
    BATCHED_INFERENCE,
    /** KV cache 量化（节省上下文内存）。 */
    KV_CACHE_QUANTIZATION,
    /** 多模态（同时接受文本 + 图像 + 音频输入）。 */
    MULTIMODAL,
    /** 函数调用 / tool use。 */
    FUNCTION_CALLING,
    /** 受限生成（JSON schema / GBNF grammar 约束）。 */
    GUIDED_GENERATION,
    /** 投机解码（draft model + verifier）。 */
    SPECULATIVE_DECODING
}
