package com.aibox.domain.inference

/**
 * 通用推理请求（v2）。
 *
 * 与 [InferenceBackendV2] 配套：一个请求描述"用哪个模型，做什么任务，喂什么输入"，
 * 不描述"用哪个后端"——后端选择由 [BackendManagerV2] 决定。
 *
 * @property taskId 请求 UUID，用于关联日志、统计、取消。
 * @property taskType 任务类型；驱动后端选择子流程。
 * @property modelId 模型 ID（与 [LoadedModel.modelPath] 的关系由宿主维护：宿主先 resolve
 *   `modelId → modelPath` 再调 [InferenceBackendV2.loadModel]，或由 BackendManagerV2 统一处理）。
 * @property inputs 多模态输入列表，按 [ModalityInput] 子类区分。文本任务通常只有一项 [ModalityInput.Text]。
 * @property config 推理配置；可覆盖模型加载时的默认 [InferenceConfigV2]（如调温度）。
 * @property stream 是否流式输出；true 调 [InferenceBackendV2.streamInfer]，false 调 [InferenceBackendV2.infer]。
 * @property sessionId 推理会话 ID（可选），用于把同一会话的日志聚合到 [LogSystem] 的会话独立日志文件。
 */
data class InferenceRequest(
    val taskId: String,
    val taskType: TaskType,
    val modelId: String,
    val inputs: List<ModalityInput>,
    val config: InferenceConfigV2,
    val stream: Boolean = false,
    val sessionId: String? = null
)

/**
 * 多模态输入项（v2）。
 *
 * 与 [InferenceResult] / [ModalityOutput] 对称：输入用 [ModalityInput]，输出用 [ModalityOutput]。
 * 后端按子类自行解析：LLM 后端只接受 [Text]；VLM 后端接受 [Text] + [Image]；
 * ASR 后端接受 [Audio]；多模态后端接受任意组合。
 */
sealed class ModalityInput {
    /** 文本输入：prompt / 系统消息 / 用户消息内容。 */
    data class Text(val content: String) : ModalityInput()

    /**
     * 图像输入。
     *
     * @property uri 本地文件 URI 或 content URI；后端负责 decode。
     * @property resolution 期望的预处理分辨率（最大边长像素）；null = 由后端按 [BackendCapabilities.maxImageResolution] 自动选。
     */
    data class Image(val uri: String, val resolution: Int? = null) : ModalityInput()

    /**
     * 音频输入。
     *
     * @property uri 本地文件 URI（wav / pcm / mp3 / 3gp / m4a 等）。
     * @property sampleRate 采样率；ASR 通常 16000；后端按需重采样。
     */
    data class Audio(val uri: String, val sampleRate: Int = 16000) : ModalityInput()

    /** 视频输入（抽帧 + 音轨）。当前多数后端未支持，预留接口。 */
    data class Video(val uri: String) : ModalityInput()
}
