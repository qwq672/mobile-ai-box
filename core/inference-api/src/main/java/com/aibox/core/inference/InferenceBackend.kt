package com.aibox.core.inference

import com.aibox.core.common.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * 推理后端抽象 — 整个 AIBox 的核心契约。
 *
 * 所有推理后端（llama.cpp / TFLite / ONNX / MLC / MNN / NPU-*）必须实现此接口。
 *
 * 反屎山原则：
 * 1. **接口零依赖**：不 import 任何具体框架类，只依赖 Kotlin 标准库和协程
 * 2. **不可变配置**：所有配置通过 [BackendConfig] 传入，不在后端内部硬编码
 * 3. **流式输出**：LLM 走 [InferCallbacks.onToken]，避免一次性 String 返回导致 OOM
 * 4. **可中断**：推理中可调用 [cancel]，释放 NPU/CPU 资源
 * 5. **失败显式**：所有错误用 [Outcome.Failure]，绝不抛异常给上层
 */
public interface InferenceBackend {

    /** 后端唯一稳定 ID（如 "llama.cpp@0.1.0"），不能变 */
    public val backendId: String

    /** 用户可见名称（可本地化） */
    public val displayName: String

    /** 支持的能力类型 */
    public val capabilities: Set<CapabilityKind>

    /** 加载模型到内存（NPU 模型走 HAP/Delegate 加载） */
    public suspend fun load(config: BackendConfig): Outcome<Unit>

    /** 卸载模型并释放资源 */
    public suspend fun unload(): Outcome<Unit>

    /** 预热：NPU 模型首次推理特别慢，提前 warmup */
    public suspend fun warmup(): Outcome<Unit>

    /** 同步推理（非流式）。短任务用 */
    public suspend fun inferOnce(request: InferenceRequest): Outcome<InferenceResult>

    /** 流式推理（LLM/音频生成） */
    public suspend fun inferStreamed(
        request: InferenceRequest,
        callbacks: InferCallbacks,
    ): Outcome<InferenceResult>

    /** 取消当前推理。必须可在任何线程调用 */
    public fun cancel()

    /** 资源指标：用于 UI 展示和负载均衡 */
    public fun metrics(): BackendMetrics

    public enum class CapabilityKind {
        CHAT,              // 文本生成
        EMBEDDING,         // 向量嵌入
        IMAGE_GENERATION,  // 文生图/图生图
        AUDIO_TTS,         // 文本转语音
        AUDIO_STT,         // 语音转文本
        MODERATION,        // 内容审查
    }
}

public data class BackendConfig(
    public val modelPath: String,
    public val modelFormat: ModelFormat,
    public val contextLength: Int = 4096,
    public val threadCount: Int = 4,
    public val gpuLayers: Int = 0,
    public val npuDelegate: NpuDelegateConfig? = null,
    public val quantization: QuantizationKind? = null,
    public val extraParams: Map<String, String> = emptyMap(),
)

public enum class ModelFormat {
    GGUF, GGML, TFLITE, ONNX, MNN, TORCHSCRIPT, SAFETENSORS
}

public enum class QuantizationKind {
    F32, F16, BF16, INT16, INT8, INT4, Q4_0, Q4_1, Q4_K, Q5_K, Q8_0
}

/**
 * NPU 委托配置。
 *
 * 优先级：高通 Hexagon V73~V81 (SNPE/QNN) > 华为 HiAI > MediaTek NeuroPilot > Android NNAPI。
 */
public data class NpuDelegateConfig(
    public val vendor: NpuVendor,
    public val targetDeviceId: String? = null,
    public val precision: NpuPrecision = NpuPrecision.INT8,
    public val allowFallbackToCpu: Boolean = true,
)

public enum class NpuVendor {
    QUALCOMM_HEXAGON,    // 高通 Hexagon NPU（V73/V75/V79/V81）
    HUAWEI_HIAI,         // 华为昇腾 NPU
    MEDIATEK_NEUROPILOT, // 联发科 APU
    GOOGLE_NNAPI,        // 通用 NNAPI（兼容所有 Android NPU）
}

public enum class NpuPrecision {
    FP32, FP16, BF16, INT16, INT8, INT4
}

public data class InferenceRequest(
    public val prompt: String,
    public val maxTokens: Int = 1024,
    public val temperature: Float = 0.7f,
    public val topP: Float = 0.95f,
    public val topK: Int = 40,
    public val stopSequences: List<String> = emptyList(),
    public val images: List<ImageInput> = emptyList(),
    public val systemPrompt: String? = null,
    public val chatHistory: List<ChatTurn> = emptyList(),
    public val stream: Boolean = false,
    public val extraParams: Map<String, String> = emptyMap(),
)

public data class ImageInput(public val uri: String, public val mimeType: String)

public data class ChatTurn(
    public val role: Role,
    public val content: String,
) {
    public enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

public data class InferenceResult(
    public val text: String = "",
    public val tokensGenerated: Int = 0,
    public val latencyMillis: Long = 0L,
    public val tokensPerSecond: Double = 0.0,
    public val finishReason: FinishReason = FinishReason.STOP,
    public val npuUsed: Boolean = false,
    public val extra: Map<String, String> = emptyMap(),
) {
    public enum class FinishReason { STOP, LENGTH, CANCELED, ERROR }
}

public interface InferCallbacks {
    public fun onToken(token: String) {}
    public fun onProgress(progress: Float) {}
    public fun onWarning(message: String) {}
    public fun onComplete(result: InferenceResult) {}
}

public data class BackendMetrics(
    public val loaded: Boolean = false,
    public val memoryMb: Long = 0L,
    public val lastInferenceMs: Long = 0L,
    public val totalInferences: Long = 0L,
    public val npuActive: Boolean = false,
    public val npuUtilization: Float = 0f,
)
