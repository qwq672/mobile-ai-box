package com.aibox.domain.audio

import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * 语音合成（Text-to-Speech）后端抽象。
 *
 * 与 [AsrProvider] 对称的零异常约定 + 流式进度模型：
 * - [startSynthesis] 返回 [Outcome]<[TtsSession]>，不抛异常。
 * - [TtsSession.progress] 是 [Flow]<[TtsProgress]>，UI collect 后可显示"播放中 / 完成 / 失败"。
 *
 * 用途：
 * - 默认 fallback：基于系统 [android.speech.tts.TextToSpeech]，零延迟、无模型。
 * - 扩展：未来可注册插件 TTS（如离线神经网络声码器 / 在线 TTS API）。
 */
interface TtsProvider {
    val id: String
    val displayName: String
    val supportedLanguages: List<String>
    val isStreaming: Boolean
    val requiresNetwork: Boolean

    /** 启动一次合成会话。 */
    suspend fun startSynthesis(config: TtsConfig): Outcome<TtsSession>

    /** 设备是否可用。 */
    fun isAvailable(): Boolean

    /** 列出当前引擎可用 voice（实现可返回 emptyList 表示不暴露多 voice）。 */
    suspend fun listVoices(): List<TtsVoice> = emptyList()
}

/**
 * TTS 合成配置。
 *
 * @param text 待合成文本。
 * @param language BCP-47 语言标签，默认 "zh-CN"。
 * @param voiceId 指定 voice id（来自 [listVoices]）；null 表示引擎默认。
 * @param speechRate 语速，1.0 = 正常，0.5 = 半速，2.0 = 双倍速。
 * @param pitch 音调，1.0 = 正常，0.5 = 低沉，2.0 = 尖锐。
 */
data class TtsConfig(
    val text: String,
    val language: String = "zh-CN",
    val voiceId: String? = null,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f
)

/**
 * TTS 合成会话句柄。
 *
 * @property sessionId 唯一会话标识。
 * @property providerId 创建此会话的 [TtsProvider.id]。
 * @property config 本会话配置。
 * @property progress 进度 Flow；自然结束 / 错误 / 用户 stop 后自动 complete。
 * @property stop 用户主动停止；幂等。
 */
data class TtsSession(
    val sessionId: String,
    val providerId: String,
    val config: TtsConfig,
    val progress: Flow<TtsProgress>,
    val stop: () -> Unit
)

/**
 * TTS 合成 / 播放进度事件。
 */
sealed class TtsProgress {
    /** 开始播放某段。 */
    data class Started(val utteranceId: String) : TtsProgress()
    /** 完成播放（自然结束或被 stop）；[allDone] 表示整个会话已结束。 */
    data class Done(val utteranceId: String, val allDone: Boolean) : TtsProgress()
    /** 播放失败。 */
    data class Failed(val utteranceId: String?, val reason: String) : TtsProgress()
}

/**
 * 可用 voice 描述（用于多 voice 选择 UI）。
 */
data class TtsVoice(
    val id: String,
    val displayName: String,
    val language: String,
    val gender: String? = null,
    val needsNetwork: Boolean = false
)
