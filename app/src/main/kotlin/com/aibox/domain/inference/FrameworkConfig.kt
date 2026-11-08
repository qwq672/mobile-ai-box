package com.aibox.domain.inference

import kotlinx.serialization.Serializable

/**
 * 推理框架 ID。每个 L1 共享运行时对应一个框架。
 *
 * - LLAMACPP: llama.cpp + ggml (cpu/vulkan/opencl),适用于 Qwen/LFM/Phi/Mistral 等 chat 模型
 * - LITERT: Google LiteRT,适用于 Gemma 4 E2B/E4B 等转换的端侧模型
 * - MNN: Alibaba MNN,主要做视觉/嵌入
 * - ONNX: ONNX Runtime,通用
 * - QNN: Qualcomm QNN,仅高通 NPU
 * - MLLM: 实验 mllm 框架
 *
 * 新增框架只需在此处加一项,即可被 [com.aibox.data.repo.FrameworkConfigRepository] 识别。
 */
@Serializable
enum class FrameworkId(val displayName: String, val defaultBackend: BackendType) {
    LLAMACPP("llama.cpp", BackendType.CPU),
    LITERT("LiteRT", BackendType.CPU),
    MNN("MNN", BackendType.CPU),
    ONNX("ONNX Runtime", BackendType.CPU),
    QNN("Qualcomm QNN", BackendType.NPU),
    MLLM("mllm (experimental)", BackendType.CPU),
    ;
}

/**
 * 框架级配置覆盖(可选)。
 *
 * - 不填(null) → 用全局 InferenceConfig
 * - 填了非 null → 该框架下加载模型时用本覆盖
 *
 * 设计要点:
 * - **混合策略**:全局默认值 + 框架覆盖,既减少配置爆炸又允许差异化
 * - **默认空**:绝大多数用户不需要为每个框架分别配置
 * - **按需扩展**:每个字段独立可选,只覆盖关心的项
 *
 * 例:
 * ```
 * FrameworkOverride(
 *     frameworkId = FrameworkId.LITERT,
 *     contextLength = 2048,    // LiteRT 上用更短 context(适配 Gemma4 安全上下文)
 *     kvQuant = "f16",         // LiteRT 不量化 KV
 *     backend = BackendType.CPU // LiteRT 默认 CPU,不走 Vulkan
 * )
 * ```
 */
@Serializable
data class FrameworkOverride(
    val frameworkId: FrameworkId,
    val backend: BackendType? = null,
    val nGpuLayers: Int? = null,
    val cpuThreads: Int? = null,
    val physicalBatch: Int? = null,
    val batch: Int? = null,
    val kvQuant: String? = null,
    val contextLength: Int? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null,
    val repeatPenalty: Float? = null
) {
    /**
     * 把本覆盖应用到 base 配置,返回合并后的新 [InferenceConfig]。
     * 非 null 字段覆盖 base, null 字段保留 base。
     */
    fun merge(base: InferenceConfig): InferenceConfig = InferenceConfig(
        backend         = backend         ?: base.backend,
        nGpuLayers      = nGpuLayers      ?: base.nGpuLayers,
        cpuThreads      = cpuThreads      ?: base.cpuThreads,
        antiRollback    = base.antiRollback,  // 框架覆盖不涉及 antiRollback,保留 base
        physicalBatch   = physicalBatch   ?: base.physicalBatch,
        batch           = batch           ?: base.batch,
        kvQuant         = kvQuant         ?: base.kvQuant,
        contextLength   = contextLength   ?: base.contextLength,
        temperature     = temperature     ?: base.temperature,
        topK            = topK            ?: base.topK,
        topP            = topP            ?: base.topP,
        repeatPenalty   = repeatPenalty   ?: base.repeatPenalty,
        systemPrompt    = base.systemPrompt   // 框架覆盖不涉及 system prompt
    )
}

/**
 * 解析当前框架应该用的最终配置。
 *
 * 优先级:frameworkOverride(若存在且非 null) > globalConfig
 *
 * @param globalConfig 用户在 Settings 里设的全局 InferenceConfig
 * @param frameworkId 当前要加载模型的框架 ID
 * @param frameworkOverrides 框架级覆盖 map,可能为空
 */
fun resolveFrameworkConfig(
    globalConfig: InferenceConfig,
    frameworkId: FrameworkId,
    frameworkOverrides: Map<FrameworkId, FrameworkOverride>
): InferenceConfig {
    val override = frameworkOverrides[frameworkId] ?: return globalConfig
    return override.merge(globalConfig)
}
