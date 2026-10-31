package com.aibox.domain.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 系统语音识别后端（默认 fallback）。
 *
 * 基于 Android 内置 [SpeechRecognizer]：无需加载模型，启动零延迟，
 * 但 (1) 需要系统已安装语音引擎（如 Google 服务），(2) 多数设备依赖网络。
 *
 * 线程模型：
 * - [SpeechRecognizer.createSpeechRecognizer] / [SpeechRecognizer.startListening] 必须在主线程。
 *   故 [startRecognition] 整体包在 `withContext(Dispatchers.Main)` 中。
 * - [AsrSession.partialResults] 是 cold [callbackFlow]；collect 时才执行 startListening，
 *   消费方（如 ViewModel.viewModelScope）默认 Dispatchers.Main.immediate，满足 SpeechRecognizer 线程约束。
 *
 * 错误语义：
 * - 设备不可用 → [Outcome.Failure]（不抛异常）。
 * - 启动异常 → [Outcome.Failure]（带 cause）。
 * - 识别中 onError（如 ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT）→ Flow complete，
 *   最后一次 partialResults 保留为最终结果。
 */
class SystemAsrProvider(
    private val appContext: Context
) : AsrProvider {

    override val id: String = "system"
    override val displayName: String = "系统语音识别"
    override val supportedLanguages: List<String> =
        listOf("zh-CN", "en-US", "en-GB", "ja-JP", "ko-KR")
    override val isStreaming: Boolean = true
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    } catch (e: Throwable) {
        LogSystem.error(TAG, "isAvailable check failed", e)
        false
    }

    override suspend fun startRecognition(config: AsrConfig): Outcome<AsrSession> =
        withContext(Dispatchers.Main) {
            if (!isAvailable()) {
                return@withContext Outcome.failure("SpeechRecognizer unavailable on this device")
            }
            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                val sessionId = UUID.randomUUID().toString()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                val flow: Flow<String> = callbackFlow {
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(p: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rms: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            LogSystem.warn(TAG, "ASR onError code=$error")
                            close()
                        }
                        override fun onResults(b: Bundle?) {
                            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()?.let { trySend(it) }
                            close()
                        }
                        override fun onPartialResults(b: Bundle?) {
                            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()?.let { trySend(it) }
                        }
                        override fun onEvent(t: Int, p: Bundle?) {}
                    })
                    recognizer.startListening(intent)
                    awaitClose {
                        runCatching { recognizer.stopListening() }
                        runCatching { recognizer.destroy() }
                    }
                }
                Outcome.success(
                    AsrSession(
                        sessionId = sessionId,
                        providerId = id,
                        config = config,
                        partialResults = flow,
                        stop = {
                            runCatching { recognizer.stopListening() }
                            runCatching { recognizer.destroy() }
                        }
                    )
                )
            } catch (e: Throwable) {
                LogSystem.error(TAG, "startRecognition failed", e)
                Outcome.failure("ASR start failed: ${e.message}", e)
            }
        }

    companion object {
        private const val TAG = "aibox.audio.asr.system"
    }
}
