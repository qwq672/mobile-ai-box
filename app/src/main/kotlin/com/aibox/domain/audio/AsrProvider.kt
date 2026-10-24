package com.aibox.domain.audio

import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * 语音识别（Automatic Speech Recognition）后端抽象。
 *
 * 设计原则：
 * - **零异常约定**：[startRecognition] 返回 [Outcome]，不抛异常。
 * - **流式结果**：通过 [AsrSession.partialResults] 的 [Flow] 暴露增量识别结果，
 *   调用方按需 collect；会话停止时 Flow 自动 complete。
 * - **懒注册**：实现类通过 [AudioProviderRegistry] 注册到宿主；UI / 上层只与接口耦合。
 *
 * 与 V2 推理抽象（[com.aibox.domain.inference.InferenceBackendV2]）的关系：
 * - 推理后端的 TaskType.AUDIO_TRANSCRIPTION 走 InferenceBackendV2 通路（按模型路由）。
 * - 本抽象面向"无模型语音前端"——直接利用系统服务（SpeechRecognizer），
 *   不需要加载模型，启动零延迟，是默认 fallback。
 */
interface AsrProvider {
    /** 全局唯一标识，如 `"system"`。 */
    val id: String
    /** 用户可见名称，如 "系统语音识别"。 */
    val displayName: String
    /** 支持的语言列表（BCP-47 / IETF tag，如 "zh-CN"）。 */
    val supportedLanguages: List<String>
    /** 是否支持流式增量结果。 */
    val isStreaming: Boolean
    /** 是否需要网络（系统 ASR 通常依赖云端服务）。 */
    val requiresNetwork: Boolean

    /** 启动一次识别会话，返回 [AsrSession]（含增量结果 Flow + 停止回调）。 */
    suspend fun startRecognition(config: AsrConfig): Outcome<AsrSession>

    /** 当前设备是否可用此 provider。 */
    fun isAvailable(): Boolean
}

/**
 * ASR 识别配置。
 *
 * @param language BCP-47 语言标签，默认 `"zh-CN"`。
 * @param sampleRate 采样率，默认 16000（系统 ASR 通常自动协商，仅用于记录意图）。
 * @param enablePunctuation 是否启用标点（取决于后端能力）。
 * @param maxDurationSeconds 最大识别时长（秒）；超时后端自行停止。
 */
data class AsrConfig(
    val language: String = "zh-CN",
    val sampleRate: Int = 16000,
    val enablePunctuation: Boolean = true,
    val maxDurationSeconds: Int = 60
)

/**
 * 一次识别会话的句柄。
 *
 * @property sessionId 唯一会话标识（UUID）。
 * @property providerId 创建此会话的 [AsrProvider.id]。
 * @property config 本会话使用的配置。
 * @property partialResults 增量识别文本 Flow；会话停止 / 自然结束时自动 complete。
 * @property stop 用户主动停止；可多次调用（幂等）。
 */
data class AsrSession(
    val sessionId: String,
    val providerId: String,
    val config: AsrConfig,
    val partialResults: Flow<String>,
    val stop: () -> Unit
)

/**
 * 一次完整识别的结果（最终态，UI 展示 / 持久化用）。
 *
 * @property text 识别文本。
 * @property language 实际识别语言。
 * @property confidence 置信度（0..1）；后端可能不提供，为 null。
 * @property durationMs 识别时长（毫秒）。
 */
data class AsrResult(
    val text: String,
    val language: String,
    val confidence: Float?,
    val durationMs: Long
)
