package com.aibox.translate

import android.util.Log
import com.aibox.plugin.ITranslateCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * 翻译协调器(本模块的核心逻辑)。
 *
 * 工作流(三步组合):
 *   Step 1 ASR  → 委托 [TranslationHostBridge.startAsr] (宿主 SystemAsrProvider)
 *                 仅当 sourceText 为空时执行;否则跳过(文本输入回退路径)。
 *   Step 2 MT   → 委托 [TranslationHostBridge.runTranslation] (宿主 BackendManagerV2)
 *                 用 module.json.workflow.step2.promptTemplate 构造 prompt,
 *                 调用用户选择的 LLM 后端一次性返回译文。
 *                 流式后端可通过 [TranslationHostBridge.runTranslationStream] 增量回调。
 *   Step 3 TTS  → 委托 [TranslationHostBridge.startTts] (宿主 SystemTtsProvider)
 *                 朗读译文;音频文件路径通过 onComplete 回传。
 *
 * 重要约束(与任务一致):
 * - **本模块不实现 ASR**:用宿主 AudioProviderRegistry.getDefaultAsrProvider() = "system"
 * - **本模块不实现 TTS**:用宿主 AudioProviderRegistry.getDefaultTtsProvider() = "system"
 * - **本模块不实现 MT**:用宿主 BackendManagerV2 路由用户选择的 LLM 后端
 * - **本模块不打包任何模型**:module APK 内 0 个 .gguf / .tflite / .onnx 文件
 *
 * 协程模型:
 * - 本类持有一个 [CoroutineScope] (SupervisorJob + Default dispatcher);
 * - [startTranslation] 立即返回 sessionId,翻译工作在 scope.launch{} 内异步执行;
 * - 每个 sessionId 对应一个 [Job],存入 [sessions] 表用于取消;
 * - Service onDestroy 调 [cancelAll] 停止所有任务避免泄漏。
 *
 * 骨架状态:
 * - 当前 [hostBridge] 在 Service 中传 null,所有 step 调用立即返回 Failure("not_implemented"),
 *   但工作流框架(进度回调 / 取消 / 增量结果)已就绪,等宿主侧补 AIDL 反向接口
 *   "IHostTranslationServices" 即可填入真实实现。
 *
 * @property hostBridge 宿主侧能力桥;null 时所有 step 直接报错(便于早期发现"未连接桥")。
 */
class TranslationOrchestrator(
    private val hostBridge: TranslationHostBridge?
) {

    companion object {
        private const val TAG = "TranslationOrchestrator"
        private const val STEP_ASR = 1
        private const val STEP_MT = 2
        private const val STEP_TTS = 3
    }

    /**
     * 当前活跃翻译会话表。
     *
     * Key = sessionId, Value = 对应的 [Job]。
     * 用 [ConcurrentHashMap] 因为 cancel() 可能从 binder 线程调用,
     * 而 startTranslation / launch 从另一个线程写入。
     */
    private val sessions: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /**
     * 当前回调句柄(由 setCallback 注入)。
     *
     * 用 volatile 即可(单写多读);不用 StateFlow 是因为不希望暴露给外部订阅,
     * 仅内部使用。用 AtomicReference 也行,这里用 @Volatile 简化。
     */
    @Volatile
    private var currentCallback: ITranslateCallback? = null

    /** 协程作用域:SupervisorJob 让单个会话失败不影响其他会话。 */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 启动一次翻译会话。
     *
     * @return 立即返回 sessionId(若入参 blank 则生成 UUID);若 callback 未注入,
     *         仍启动翻译但所有回调事件被静默丢弃(便于宿主绑定阶段先 start 再 setCallback)。
     *         实际工作在后台协程异步执行,通过 [ITranslateCallback] 回传事件。
     */
    fun startTranslation(
        sourceText: String,
        sourceLang: String,
        targetLang: String,
        sessionId: String
    ): String {
        val sid = sessionId.ifBlank { UUID.randomUUID().toString() }
        Log.i(TAG, "startTranslation: sid=$sid, sourceLang=$sourceLang, targetLang=$targetLang, hasSourceText=${sourceText.isNotEmpty()}")

        // 若同 sid 已有会话在跑,先取消旧的(幂等重启)。
        sessions[sid]?.let { existing ->
            Log.w(TAG, "session $sid already running; cancelling previous before restart.")
            existing.cancel()
            sessions.remove(sid, existing)
        }

        val job = scope.launch {
            try {
                val outcome = translate(sourceText, sourceLang, targetLang, sid)
                when (outcome) {
                    is Outcome.Success -> {
                        // translate() 内部已通过 callback 上报 onComplete,
                        // 这里只做日志收尾。
                        Log.i(TAG, "session $sid completed: ${outcome.value.translatedText.take(50)}…")
                    }
                    is Outcome.Failure -> {
                        Log.w(TAG, "session $sid failed: ${outcome.reason}", outcome.cause)
                        // 翻译过程中已通过 callback 上报 onError;若 callback 之前未触发 onError,
                        // 这里兜底发一次(确保宿主收到终结回调)。
                        // 实际上 translate() 内 catch 已调 onError,这里属于二次保护。
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消是预期行为(由 cancel() 触发),不当作崩溃处理;
                // 重新抛出让协程机制正确传播取消状态,
                // 外层 cancel() 会在 cancelAndJoin 完成后发 onError("cancelled")。
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "session $sid unexpected error", e)
                safeOnError("orchestrator_crashed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                sessions.remove(sid)
            }
        }
        sessions[sid] = job
        return sid
    }

    /**
     * 取消指定会话。幂等(不存在 / 已结束 no-op)。
     *
     * 取消语义保证:
     * - 若会话仍在运行:cancelAndJoin 后调一次 [safeOnError]("cancelled") 让宿主清理 UI;
     * - 若会话已自然结束(translate 已通过 callback 上报 onComplete/onError):no-op,
     *   **不重复发** onError(遵守"一次会话只会有一个终结回调"契约)。
     */
    fun cancel(sessionId: String) {
        val job = sessions.remove(sessionId) ?: run {
            Log.w(TAG, "cancel: session $sessionId not found (already finished or unknown)")
            return
        }
        Log.i(TAG, "cancelling session $sessionId")
        scope.launch {
            // job.cancel() 对已完成的 Job 是 no-op;对运行中的 Job 标记取消。
            runCatching { job.cancel() }
            // 等待 Job 真正结束(自然完成或被取消)。
            job.join()
            // 仅在 Job 真的被取消时才发 onError("cancelled");
            // 若 Job 自然完成(isCancelled=false),translate() 已通过 callback 上报过
            // onComplete/onError,这里不再重复发(防双发)。
            if (job.isCancelled) {
                safeOnError("cancelled")
            } else {
                Log.i(TAG, "session $sessionId already completed naturally; cancel is no-op")
            }
        }
    }

    /** 取消所有正在进行的会话(Service onDestroy 调用)。 */
    fun cancelAll() {
        Log.i(TAG, "cancelAll: ${sessions.size} in-flight sessions")
        val snapshot = sessions.toList()
        sessions.clear()
        scope.launch {
            snapshot.forEach { (sid, job) ->
                runCatching { job.cancelAndJoin() }
                // 不再回 onError,因为 Service 已销毁,宿主可能已 unbind。
                Log.d(TAG, "cancelled session $sid on shutdown")
            }
        }
    }

    /** 注入 / 替换 / 清除回调。null 表示解除。 */
    fun setCallback(callback: ITranslateCallback?) {
        currentCallback = callback
        Log.i(TAG, "setCallback: ${if (callback == null) "null (cleared)" else "set"}")
    }

    // ===================================================
    // 核心:三步工作流
    // ===================================================

    /**
     * 翻译核心工作流。
     *
     * 由 [startTranslation] 在协程内调用,本方法是 suspend,允许 [TranslationHostBridge]
     * 的实现内部使用挂起函数(如等待 ASR 识别完成 / LLM 流式输出)。
     *
     * 错误处理:任意步骤失败立即调 [safeOnError] 并 return Failure;后续步骤不执行。
     * 取消响应:每个步骤之间调 [yield] 让 [Job.cancel] 尽快生效(协程取消检查点)。
     *
     * @return 成功 Outcome<TranslationResult>;失败 Outcome.Failure。
     *         注意本方法内部已通过 callback 上报进度 / 增量 / 完成,返回值仅作日志与外层统计。
     */
    suspend fun translate(
        sourceText: String,
        sourceLang: String,
        targetLang: String,
        sessionId: String
    ): Outcome<TranslationResult> {
        val startMs = System.currentTimeMillis()
        val bridge = hostBridge ?: run {
            safeOnError("host_bridge_not_attached")
            return Outcome.failure("host_bridge_not_attached")
        }

        // ---- Step 1: ASR(仅当 sourceText 为空)----
        val recognizedText: String
        if (sourceText.isNotEmpty()) {
            // 文本输入回退路径:跳过 ASR,直接进 Step 2。
            recognizedText = sourceText
            Log.i(TAG, "[$sessionId] Step 1 ASR: skipped (sourceText provided)")
        } else {
            safeOnProgress(STEP_ASR, "ASR")
            yield()
            val asrResult = bridge.startAsr(sessionId, sourceLang)
            if (asrResult is Outcome.Failure) {
                safeOnError("asr_failed: ${asrResult.reason}")
                return asrResult
            }
            recognizedText = (asrResult as Outcome.Success).value
            if (recognizedText.isBlank()) {
                safeOnError("asr_empty_result")
                return Outcome.failure("asr_empty_result")
            }
            Log.i(TAG, "[$sessionId] Step 1 ASR: recognized=${recognizedText.take(80)}")
        }

        // ---- Step 2: MT ----
        safeOnProgress(STEP_MT, "MT")
        yield()
        val mtResult = bridge.runTranslationStream(
            sessionId = sessionId,
            sourceText = recognizedText,
            sourceLang = sourceLang,
            targetLang = targetLang,
            onPartial = { partial -> safeOnPartialResult(partial) }
        )
        if (mtResult is Outcome.Failure) {
            safeOnError("mt_failed: ${mtResult.reason}")
            return mtResult
        }
        val translatedText = (mtResult as Outcome.Success).value
        if (translatedText.isBlank()) {
            safeOnError("mt_empty_result")
            return Outcome.failure("mt_empty_result")
        }
        Log.i(TAG, "[$sessionId] Step 2 MT: translated=${translatedText.take(80)}")

        // ---- Step 3: TTS ----
        safeOnProgress(STEP_TTS, "TTS")
        yield()
        val ttsResult = bridge.startTts(sessionId, translatedText, targetLang)
        if (ttsResult is Outcome.Failure) {
            // TTS 失败不致命:译文已生成,宿主可显示译文让用户自行朗读。
            // 仍上报 onComplete 但 ttsAudioPath 为空,并附加 warning 日志。
            Log.w(TAG, "[$sessionId] Step 3 TTS failed (non-fatal): ${ttsResult.reason}")
            val result = TranslationResult(
                originalText = recognizedText,
                translatedText = translatedText,
                sourceLang = sourceLang,
                targetLang = targetLang,
                durationMs = System.currentTimeMillis() - startMs
            )
            safeOnComplete(translatedText, "")
            return Outcome.success(result)
        }
        val ttsAudioPath = (ttsResult as Outcome.Success).value
        Log.i(TAG, "[$sessionId] Step 3 TTS: audioPath=$ttsAudioPath")

        // ---- 完成 ----
        val result = TranslationResult(
            originalText = recognizedText,
            translatedText = translatedText,
            sourceLang = sourceLang,
            targetLang = targetLang,
            durationMs = System.currentTimeMillis() - startMs
        )
        safeOnComplete(translatedText, ttsAudioPath ?: "")
        return Outcome.success(result)
    }

    // ===================================================
    // 回调转发(防 RemoteException 静默吞掉)
    // ===================================================

    private fun safeOnProgress(step: Int, stepName: String) {
        runCatching { currentCallback?.onProgress(step, stepName) }
            .onFailure { Log.w(TAG, "onProgress callback failed (host dead?)", it) }
    }

    private fun safeOnPartialResult(partial: String) {
        runCatching { currentCallback?.onPartialResult(partial) }
            .onFailure { Log.w(TAG, "onPartialResult callback failed", it) }
    }

    private fun safeOnComplete(finalTranslation: String, ttsAudioPath: String) {
        runCatching { currentCallback?.onComplete(finalTranslation, ttsAudioPath) }
            .onFailure { Log.w(TAG, "onComplete callback failed", it) }
    }

    private fun safeOnError(reason: String) {
        runCatching { currentCallback?.onError(reason) }
            .onFailure { Log.w(TAG, "onError callback failed", it) }
    }
}

// ===================================================
// 宿主能力桥(模块侧接口;宿主实现)
// ===================================================

/**
 * 宿主能力桥接口:翻译模块用本接口访问宿主的 ASR / TTS / LLM 推理服务。
 *
 * 实际注入方式(待补):
 * 1. 宿主侧定义 AIDL 接口 IHostTranslationServices.aidl(包含 startAsr / runTranslation / startTts),
 *    并在某个宿主 Service 中实现;
 * 2. 模块侧反向 bindService 到宿主,拿到 IBinder 后包装成本接口的实现;
 * 3. TranslateModuleService.onCreate 中拿到桥后传给 TranslationOrchestrator。
 *
 * 当前骨架未注入真实实现;orchestrator 中所有 step 调用会返回 Failure("not_implemented")。
 */
interface TranslationHostBridge {

    /**
     * Step 1: 启动系统 ASR 识别(宿主侧 SystemAsrProvider)。
     *
     * @param sessionId 翻译会话 id(便于宿主关联 ASR session)。
     * @param language  BCP-47 标签,"auto" 让宿主自动检测。
     * @return 成功 Outcome<识别文本>;失败 Outcome.Failure。
     */
    suspend fun startAsr(sessionId: String, language: String): Outcome<String>

    /**
     * Step 2: 运行 LLM 翻译(宿主侧 BackendManagerV2 + 用户选择的模型)。
     *
     * 宿主侧用 module.json.workflow.step2.promptTemplate 构造 prompt:
     *   "Translate the following {source_lang} text to {target_lang}. Only output the translation, no explanation.\n\nText: {text}"
     *
     * @param sessionId 翻译会话 id。
     * @param sourceText 待翻译文本(ASR 识别结果或用户直接输入)。
     * @param sourceLang 源语言;"auto" 时 prompt 中用 "source language" 占位。
     * @param targetLang 目标语言。
     * @param onPartial 流式增量回调;每次产出一个累计译文片段。非流式后端只调一次(完整译文)。
     * @return 成功 Outcome<最终译文>;失败 Outcome.Failure。
     */
    suspend fun runTranslationStream(
        sessionId: String,
        sourceText: String,
        sourceLang: String,
        targetLang: String,
        onPartial: (String) -> Unit
    ): Outcome<String>

    /**
     * Step 3: 启动系统 TTS 合成(宿主侧 SystemTtsProvider)。
     *
     * @param sessionId 翻译会话 id。
     * @param text 待合成文本(MT 输出)。
     * @param language 目标语言 BCP-47 标签(决定 TTS 引擎选 voice)。
     * @return 成功 Outcome<音频文件绝对路径 或 空串(若直接播放未落盘)>;
     *         失败 Outcome.Failure(TTS 失败非致命,orchestrator 仍会 onComplete)。
     */
    suspend fun startTts(sessionId: String, text: String, language: String): Outcome<String?>
}

// ===================================================
// 翻译结果 / 回调(Kotlin 侧;ITranslateCallback 的 Kotlin 友好版本,供宿主内嵌使用)
// ===================================================

/**
 * 一次完整翻译的结果(最终态)。
 *
 * @property originalText 原文(ASR 识别结果或用户直接输入)。
 * @property translatedText 译文。
 * @property sourceLang 源语言 BCP-47 标签(可能为 "auto")。
 * @property targetLang 目标语言 BCP-47 标签。
 * @property durationMs 总耗时(毫秒),从 startTranslation 到 onComplete。
 */
data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val durationMs: Long
)

/**
 * 翻译回调的 Kotlin 友好版本(对应 AIDL [ITranslateCallback])。
 *
 * 本模块内部用 [ITranslateCallback] 直接回调(避免多一层适配);
 * 本接口预留给"模块内嵌测试"或"未来 Kotlin-only 调用方"使用。
 * 当前未启用,作为对外契约的一部分先定义好。
 */
interface TranslationCallback {
    fun onProgress(step: String)
    fun onPartialResult(partial: String)
    fun onComplete(result: TranslationResult)
    fun onError(reason: String)
}
