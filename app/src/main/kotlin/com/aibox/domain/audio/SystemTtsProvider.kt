package com.aibox.domain.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * 系统语音合成后端（默认 fallback）。
 *
 * 基于 Android 内置 [TextToSpeech]：
 * - 实例在构造时即异步 init（init 回调在主线程触发）。
 * - [isAvailable] 始终 true（系统 TTS 引擎一般存在；缺引擎时 init 失败 → startSynthesis 返回 failure）。
 * - 单实例多会话复用：第二次 speak 自动 QUEUE_FLUSH 中断前一次。
 *
 * 线程模型：
 * - speak / setLanguage / setSpeechRate / setPitch 必须在主线程。
 *   [startSynthesis] 整体包在 `withContext(Dispatchers.Main)` 中；Flow body 由消费方调起，
 *   viewModelScope 默认 Dispatchers.Main.immediate，满足 TextToSpeech 线程约束。
 */
class SystemTtsProvider(
    private val appContext: Context
) : TtsProvider {

    override val id: String = "system"
    override val displayName: String = "系统语音合成"
    override val supportedLanguages: List<String> =
        listOf("zh-CN", "en-US", "en-GB", "ja-JP", "ko-KR")
    override val isStreaming: Boolean = true
    override val requiresNetwork: Boolean = false

    private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false

    init {
        // 提前触发异步 init；init 回调在主线程触发
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            LogSystem.info(TAG, "TTS init status=$status ready=$ready")
        }
    }

    override fun isAvailable(): Boolean = true

    override suspend fun listVoices(): List<TtsVoice> = withContext(Dispatchers.Main) {
        if (!ready) return@withContext emptyList()
        tts?.voices?.map { v ->
            TtsVoice(
                id = v.name,
                displayName = v.name,
                language = v.locale?.toLanguageTag() ?: "und",
                needsNetwork = v.isNetworkConnectionRequired
            )
        } ?: emptyList()
    }

    override suspend fun startSynthesis(config: TtsConfig): Outcome<TtsSession> =
        withContext(Dispatchers.Main) {
            if (!ready) {
                return@withContext Outcome.failure("TTS 引擎未就绪，请稍后重试")
            }
            try {
                val sessionId = UUID.randomUUID().toString()
                val utteranceId = "utt_$sessionId"
                val flow: Flow<TtsProgress> = callbackFlow {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {
                            trySend(TtsProgress.Started(id ?: utteranceId))
                        }
                        override fun onDone(id: String?) {
                            trySend(TtsProgress.Done(id ?: utteranceId, true))
                            close()
                        }
                        override fun onError(id: String?) {
                            trySend(TtsProgress.Failed(id, "TTS utterance failed"))
                            close()
                        }
                    })
                    tts?.language = Locale.forLanguageTag(config.language)
                    tts?.setSpeechRate(config.speechRate)
                    tts?.setPitch(config.pitch)
                    tts?.speak(config.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                    awaitClose { runCatching { tts?.stop() } }
                }
                Outcome.success(
                    TtsSession(
                        sessionId = sessionId,
                        providerId = id,
                        config = config,
                        progress = flow,
                        stop = { runCatching { tts?.stop() } }
                    )
                )
            } catch (e: Throwable) {
                LogSystem.error(TAG, "startSynthesis failed", e)
                Outcome.failure("TTS start failed: ${e.message}", e)
            }
        }

    companion object {
        private const val TAG = "aibox.audio.tts.system"
    }
}
