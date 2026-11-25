package com.aibox.domain.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Constructor
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线 ONNX 语音识别后端（sherpa-onnx，2026-11-25）。
 *
 * 设计要点（与 [SystemAsrProvider] 的差异）：
 * - **零编译期依赖**：本类不 import 任何 `com.k2fsa.sherpa.onnx.*` 类型，全部通过反射访问。
 *   这样默认 CI / 出包（不带 `-Paibox.asr.sherpa=true`）即使没有引入 sherpa-onnx AAR 也能编译通过；
 *   [isAvailable] 在类不存在时返回 false，registry 自动回退到 system provider，绝不崩溃。
 * - **真能力需开关 + 模型**：用 `-Paibox.asr.sherpa=true` 构建会把 sherpa-onnx AAR 打进包；
 *   再把中文流式 Zipformer 模型（encoder/decoder/joiner/tokens 四个文件）放到 [modelDir]，
 *   [isAvailable] 才会返回 true。
 * - **流式**：AudioRecord 以 16kHz/mono/PCM16 采集，分块喂给 OnlineStream，
 *   `isReady` 时 `decode`，`getResult().text` 作为增量结果吐给 [AsrSession.partialResults]。
 *
 * 反射访问的 sherpa-onnx API（v1.13.5，已联网核实）：
 * ```
 * FeatureConfig(sampleRate=16000, featureDim=80)
 * OnlineTransducerModelConfig(encoder, decoder, joiner)
 * OnlineModelConfig(transducer, tokens, numThreads, provider)
 * OnlineRecognizerConfig(featConfig, modelConfig, enableEndpoint, decodingMethod)
 * OnlineRecognizer(config) -> createStream() / isReady / decode / getResult / isEndpoint / reset
 * OnlineStream.acceptWaveform(FloatArray, Int)
 * OnlineRecognizerResult.text : String
 * ```
 */
class OnnxAsrProvider(
    private val modelDir: File = File("/sdcard/aibox/models/sherpa-asr")
) : AsrProvider {

    override val id: String = "onnx"
    override val displayName: String = "离线语音识别（ONNX）"
    override val supportedLanguages: List<String> = listOf("zh-CN", "en-US")
    override val isStreaming: Boolean = true
    override val requiresNetwork: Boolean = false

    // 反射缓存：只加载一次
    private var recognizerCtor: Constructor<*>? = null
    private var configClass: Class<*>? = null
    private var streamClass: Class<*>? = null
    private var checked = false

    /** native 类是否存在（AAR 是否打进包）。 */
    private fun nativeReady(): Boolean {
        if (checked) return recognizerCtor != null
        checked = true
        try {
            val recognizerClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
            configClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
            streamClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineStream")
            // OnlineRecognizer 主构造器 = (AssetManager?, OnlineRecognizerConfig)，两参
            @Suppress("UNCHECKED_CAST")
            recognizerCtor = recognizerClass.getConstructor(
                android.content.res.AssetManager::class.java, configClass
            )
            LogSystem.info(TAG, "sherpa-onnx native classes found")
        } catch (e: Throwable) {
            LogSystem.warn(TAG, "sherpa-onnx native classes not available: ${e.message}")
            recognizerCtor = null
        }
        return recognizerCtor != null
    }

    /** 模型四件套是否齐全。 */
    private fun modelReady(): Boolean {
        val encoder = File(modelDir, "encoder.onnx")
        val decoder = File(modelDir, "decoder.onnx")
        val joiner = File(modelDir, "joiner.onnx")
        val tokens = File(modelDir, "tokens.txt")
        val ok = encoder.exists() && decoder.exists() && joiner.exists() && tokens.exists()
        if (!ok) {
            LogSystem.warn(
                TAG,
                "model files incomplete in ${modelDir.absolutePath}: " +
                    "encoder=${encoder.exists()} decoder=${decoder.exists()} " +
                    "joiner=${joiner.exists()} tokens=${tokens.exists()}"
            )
        }
        return ok
    }

    override fun isAvailable(): Boolean = nativeReady() && modelReady()

    override suspend fun startRecognition(config: AsrConfig): Outcome<AsrSession> =
        withContext(Dispatchers.IO) {
            if (!nativeReady()) {
                return@withContext Outcome.failure(
                    "ONNX 后端未启用：请用 -Paibox.asr.sherpa=true 重新构建"
                )
            }
            if (!modelReady()) {
                return@withContext Outcome.failure(
                    "ONNX 模型缺失：请将模型放到 ${modelDir.absolutePath}"
                )
            }
            try {
                val ctor = recognizerCtor ?: return@withContext Outcome.failure("recognizer 构造器不可用")
                val cfgCls = configClass ?: return@withContext Outcome.failure("config 类不可用")

                // Kotlin data class（全默认参数）会生成无参构造 + var 属性的 setter。
                // 统一用「无参构造 + setter」组装，避免依赖构造参数个数/顺序。
                val featClass = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
                val feat = featClass.getConstructor().newInstance()
                setField(feat, "setSampleRate", java.lang.Integer.TYPE, 16000)
                setField(feat, "setFeatureDim", java.lang.Integer.TYPE, 80)

                val transducerClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
                val transducer = transducerClass.getConstructor().newInstance()
                setField(transducer, "setEncoder", String::class.java, File(modelDir, "encoder.onnx").absolutePath)
                setField(transducer, "setDecoder", String::class.java, File(modelDir, "decoder.onnx").absolutePath)
                setField(transducer, "setJoiner", String::class.java, File(modelDir, "joiner.onnx").absolutePath)

                val modelClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
                val modelCfg = modelClass.getConstructor().newInstance()
                setField(modelCfg, "setTransducer", transducerClass, transducer)
                setField(modelCfg, "setTokens", String::class.java, File(modelDir, "tokens.txt").absolutePath)
                setField(modelCfg, "setNumThreads", java.lang.Integer.TYPE, 2)
                setField(modelCfg, "setProvider", String::class.java, "cpu")

                val recCfg = cfgCls.getConstructor().newInstance()
                setField(recCfg, "setFeatConfig", featClass, feat)
                setField(recCfg, "setModelConfig", modelClass, modelCfg)
                setField(recCfg, "setEnableEndpoint", java.lang.Boolean.TYPE, true)
                setField(recCfg, "setDecodingMethod", String::class.java, "greedy_search")

                // 构造 OnlineRecognizer(assetManager=null, config)
                val sClass: Class<*> = streamClass ?: return@withContext Outcome.failure("stream 类不可用")
                val recognizer = ctor.newInstance(null, recCfg)
                val createStream = recognizer.javaClass.getMethod("createStream")
                val isReady = recognizer.javaClass.getMethod("isReady", sClass)
                val decode = recognizer.javaClass.getMethod("decode", sClass)
                val getResult = recognizer.javaClass.getMethod("getResult", sClass)
                val isEndpoint = recognizer.javaClass.getMethod("isEndpoint", sClass)
                val reset = recognizer.javaClass.getMethod("reset", sClass)
                val releaseRecognizer = recognizer.javaClass.getMethod("release")

                val acceptWaveform = sClass.getMethod(
                    "acceptWaveform", FloatArray::class.java, java.lang.Integer.TYPE
                )
                val releaseStream = sClass.getMethod("release")

                val sessionId = UUID.randomUUID().toString()
                val releaseRequested = AtomicBoolean(false)

                val flow: Flow<String> = callbackFlow {
                    val stream = createStream.invoke(recognizer)
                    val buffer = ShortArray(320) // 20ms @ 16kHz
                    var recorder: AudioRecord? = null
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(
                            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                        )
                        recorder = AudioRecord(
                            MediaRecorder.AudioSource.MIC, 16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            maxOf(minBuf, 640)
                        )
                        recorder.startRecording()
                        while (!releaseRequested.get()) {
                            val n = recorder.read(buffer, 0, buffer.size)
                            if (n <= 0) continue
                            val floats = FloatArray(n) { buffer[it] / 32768f }
                            acceptWaveform.invoke(stream, floats, 16000)
                            while (isReady.invoke(recognizer, stream) as Boolean) {
                                decode.invoke(recognizer, stream)
                            }
                            val result = getResult.invoke(recognizer, stream)
                            val text = result.javaClass.getMethod("getText").invoke(result) as? String
                            if (!text.isNullOrBlank()) trySend(text)
                            if (isEndpoint.invoke(recognizer, stream) as Boolean) {
                                reset.invoke(recognizer, stream)
                            }
                        }
                    } catch (e: Throwable) {
                        LogSystem.error(TAG, "ONNX recognition loop failed", e)
                    } finally {
                        runCatching { recorder?.stop() }
                        runCatching { recorder?.release() }
                        runCatching { releaseStream.invoke(stream) }
                        runCatching { releaseRecognizer.invoke(recognizer) }
                    }
                    awaitClose { releaseRequested.set(true) }
                }

                Outcome.success(
                    AsrSession(
                        sessionId = sessionId,
                        providerId = id,
                        config = config,
                        partialResults = flow,
                        stop = { releaseRequested.set(true) }
                    )
                )
            } catch (e: Throwable) {
                LogSystem.error(TAG, "startRecognition failed", e)
                Outcome.failure("ONNX ASR 启动失败: ${e.message}", e)
            }
        }

    private fun setField(target: Any, method: String, paramType: Class<*>, value: Any?) {
        target.javaClass.getMethod(method, paramType).invoke(target, value)
    }

    companion object {
        private const val TAG = "aibox.audio.asr.onnx"
        /** 模型默认目录；可用环境变量或构造参数覆盖。 */
        const val DEFAULT_MODEL_DIR = "/sdcard/aibox/models/sherpa-asr"
    }
}
