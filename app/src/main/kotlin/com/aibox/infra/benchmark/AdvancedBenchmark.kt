package com.aibox.infra.benchmark

import com.aibox.domain.inference.InferenceBackendV2
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.InferenceRequest
import com.aibox.domain.inference.LoadedModel
import com.aibox.domain.inference.ModalityInput
import com.aibox.domain.inference.ModalityOutput
import com.aibox.domain.inference.TaskType
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.collect
import java.util.UUID

/**
 * Layer 2 高级基准：端到端场景测试。
 *
 * 与 [BasicBenchmark] 不同，[AdvancedBenchmark] 测的是"真实使用场景"的体验：
 * - [coldStart] 冷启动（含模型加载）
 * - [shortChat] 短消息聊天
 * - [multiTurn] 多轮累积上下文
 * - [longPrompt] 长 prompt 处理
 * - [streamingSmoothness] 流式平滑度（token 间隔方差，检测 GC 抖动 / NPU 节流）
 *
 * 所有方法返回结构化的高级结果，便于 UI 上下文展示。
 */
object AdvancedBenchmark {

    private const val TAG = "aibox.benchmark.advanced"

    // ============== 冷启动场景 ==============

    /**
     * 冷启动场景：从"模型未加载"到"首条响应完成"的端到端时长。
     *
     * 包含：
     * 1. [InferenceBackendV2.loadModel] 时长（模型文件 mmap / KV 预分配 / 编译图等）
     * 2. 首 token 延迟（TTFT）
     * 3. 整次响应时长
     *
     * 调用方负责确保调用前模型未加载（如先 [InferenceBackendV2.unload]）。
     *
     * @param modelPath 模型文件路径
     * @param config 加载配置（含 optimizations.preloadModel 等开关）
     */
    suspend fun coldStart(
        backend: InferenceBackendV2,
        modelPath: String,
        config: InferenceConfigV2
    ): ColdStartResult {
        val sessionId = LogSystem.startSession("benchmark.cold", "${backend.getName()}_${modelPath.substringAfterLast('/')}")
        LogSystem.info(TAG, "=== ColdStart: backend=${backend.getName()} model=$modelPath", sessionId = sessionId)
        return try {
            val loadStart = System.currentTimeMillis()
            val loadOutcome = backend.loadModel(modelPath, config)
            val loadedAt = System.currentTimeMillis()
            val loadTime = loadedAt - loadStart

            val loaded = loadOutcome.getOrNull()
                ?: return ColdStartResult(
                    loadTimeMs = loadTime,
                    firstTokenMs = 0L,
                    totalResponseMs = 0L
                ).also {
                    LogSystem.error(TAG, "ColdStart: loadModel failed: ${loadOutcome.reasonOrNull()}", sessionId = sessionId)
                }

            // 跑一次短生成
            val request = InferenceRequest(
                taskId = UUID.randomUUID().toString(),
                taskType = TaskType.TEXT_GENERATION,
                modelId = loaded.id,
                inputs = listOf(ModalityInput.Text("你好")),
                config = config.copy(maxTokens = 32),
                stream = true,
                sessionId = sessionId
            )
            val first = measureFirstTokenAndTotal(backend, request, loaded, sessionId)
            backend.unload(loaded)

            ColdStartResult(
                loadTimeMs = loadTime,
                firstTokenMs = first.first,
                totalResponseMs = first.second
            )
        } catch (t: Throwable) {
            LogSystem.error(TAG, "ColdStart threw: ${t.message}", t, sessionId = sessionId)
            ColdStartResult(loadTimeMs = 0L, firstTokenMs = 0L, totalResponseMs = 0L)
        } finally {
            LogSystem.endSession(sessionId, "ColdStart end")
        }
    }

    // ============== 短消息聊天场景 ==============

    /**
     * 短消息聊天：单轮 "你好" → 回复。
     *
     * @param message 用户输入文本
     */
    suspend fun shortChat(
        backend: InferenceBackendV2,
        model: LoadedModel,
        message: String = "你好"
    ): ChatResult {
        val sessionId = LogSystem.startSession("benchmark.chat", "${backend.getName()}_short")
        LogSystem.info(TAG, "=== ShortChat: backend=${backend.getName()} msg=${message}", sessionId = sessionId)
        return try {
            val request = InferenceRequest(
                taskId = UUID.randomUUID().toString(),
                taskType = TaskType.TEXT_GENERATION,
                modelId = model.id,
                inputs = listOf(ModalityInput.Text(message)),
                config = model.config.copy(maxTokens = 128),
                stream = true,
                sessionId = sessionId
            )
            val sb = StringBuilder()
            val (firstTokenMs, totalMs, tokenCount) = collectStream(backend, request, model, sessionId) { text ->
                sb.append(text)
            }
            ChatResult(
                ttftMs = firstTokenMs,
                tgTps = if (tokenCount > 0 && totalMs - firstTokenMs > 0)
                    tokenCount.toDouble() * 1000.0 / (totalMs - firstTokenMs) else 0.0,
                totalTokens = tokenCount,
                responseText = sb.toString()
            )
        } catch (t: Throwable) {
            LogSystem.error(TAG, "ShortChat threw: ${t.message}", t, sessionId = sessionId)
            ChatResult(ttftMs = 0L, tgTps = 0.0, totalTokens = 0, responseText = "")
        } finally {
            LogSystem.endSession(sessionId, "ShortChat end")
        }
    }

    // ============== 多轮累积上下文场景 ==============

    /**
     * 多轮累积上下文：模拟真实聊天，每轮追加一条 user message + 生成一条 response。
     *
     * 测量每轮的 TTFT / TG tps，以及每轮的累积上下文 token 长度（粗估）。
     * 用于检测"上下文越长，TTFT 越慢"的退化曲线。
     *
     * @param turns 轮数（默认 10 轮）
     */
    suspend fun multiTurn(
        backend: InferenceBackendV2,
        model: LoadedModel,
        turns: Int = 10
    ): MultiTurnResult {
        val sessionId = LogSystem.startSession("benchmark.multi", "${backend.getName()}_x${turns}")
        LogSystem.info(TAG, "=== MultiTurn: backend=${backend.getName()} turns=$turns", sessionId = sessionId)

        val ttftPerTurn = mutableListOf<Long>()
        val tgTpsPerTurn = mutableListOf<Double>()
        val contextGrowth = mutableListOf<Int>()

        // 模拟历史：把所有 user + assistant 累积成 prompt
        val history = StringBuilder()
        var cumulativeTokens = 0

        return try {
            repeat(turns) { i ->
                val userMsg = "第${i + 1}条消息：请告诉我一些关于机器学习的知识。"
                history.append("User: ").append(userMsg).append("\n")
                val promptTokens = userMsg.length / 4 + cumulativeTokens
                cumulativeTokens = promptTokens
                contextGrowth.add(cumulativeTokens)

                val request = InferenceRequest(
                    taskId = UUID.randomUUID().toString(),
                    taskType = TaskType.TEXT_GENERATION,
                    modelId = model.id,
                    inputs = listOf(ModalityInput.Text(history.toString())),
                    config = model.config.copy(maxTokens = 64),
                    stream = true,
                    sessionId = sessionId
                )
                val sb = StringBuilder()
                val (firstTokenMs, totalMs, tokenCount) = collectStream(backend, request, model, sessionId) { text ->
                    sb.append(text)
                }
                ttftPerTurn.add(firstTokenMs)
                tgTpsPerTurn.add(
                    if (tokenCount > 0 && totalMs - firstTokenMs > 0)
                        tokenCount.toDouble() * 1000.0 / (totalMs - firstTokenMs) else 0.0
                )
                history.append("Assistant: ").append(sb).append("\n")
                cumulativeTokens += sb.length / 4
                LogSystem.info(TAG, "turn #${i + 1}: ttft=${firstTokenMs}ms tgTps=${"%.2f".format(tgTpsPerTurn.last())} ctx=$cumulativeTokens", sessionId = sessionId)
            }
            MultiTurnResult(ttftPerTurn = ttftPerTurn, tgTpsPerTurn = tgTpsPerTurn, contextGrowth = contextGrowth)
        } catch (t: Throwable) {
            LogSystem.error(TAG, "MultiTurn threw: ${t.message}", t, sessionId = sessionId)
            MultiTurnResult(ttftPerTurn, tgTpsPerTurn, contextGrowth)
        } finally {
            LogSystem.endSession(sessionId, "MultiTurn end")
        }
    }

    // ============== 长 prompt 处理场景 ==============

    /**
     * 长 prompt 处理：构造约 [promptTokens] 个 token 的 prompt，测 PP t/s 与 TTFT。
     *
     * @param promptTokens 目标 prompt 长度（token 数；用字符数近似）
     */
    suspend fun longPrompt(
        backend: InferenceBackendV2,
        model: LoadedModel,
        promptTokens: Int
    ): LongPromptResult {
        val sessionId = LogSystem.startSession("benchmark.long", "${backend.getName()}_${promptTokens}t")
        LogSystem.info(TAG, "=== LongPrompt: backend=${backend.getName()} targetTokens=$promptTokens", sessionId = sessionId)
        return try {
            val prompt = buildPlaceholder(promptTokens)
            val request = InferenceRequest(
                taskId = UUID.randomUUID().toString(),
                taskType = TaskType.TEXT_GENERATION,
                modelId = model.id,
                inputs = listOf(ModalityInput.Text(prompt)),
                config = model.config.copy(maxTokens = 16),  // 只生成 16 个 token，专注于 PP 测量
                stream = true,
                sessionId = sessionId
            )
            val (firstTokenMs, _, _) = collectStream(backend, request, model, sessionId) { /* ignore text */ }
            val ppTps = if (firstTokenMs > 0) promptTokens.toDouble() * 1000.0 / firstTokenMs else 0.0
            LongPromptResult(ppTps = ppTps, ttftMs = firstTokenMs, promptTokens = promptTokens)
        } catch (t: Throwable) {
            LogSystem.error(TAG, "LongPrompt threw: ${t.message}", t, sessionId = sessionId)
            LongPromptResult(ppTps = 0.0, ttftMs = 0L, promptTokens = promptTokens)
        } finally {
            LogSystem.endSession(sessionId, "LongPrompt end")
        }
    }

    // ============== 流式平滑度场景 ==============

    /**
     * 流式平滑度：记录每个 token 之间的时间间隔，计算方差、最大间隔、最大间隔位置。
     *
     * 方差越小越平滑；最大间隔出现在中段通常意味着 GC 暂停或 NPU 节流抖动。
     *
     * @param targetTokens 目标生成 token 数（默认 100）
     */
    suspend fun streamingSmoothness(
        backend: InferenceBackendV2,
        model: LoadedModel,
        targetTokens: Int = 100
    ): SmoothnessResult {
        val sessionId = LogSystem.startSession("benchmark.smooth", "${backend.getName()}_${targetTokens}t")
        LogSystem.info(TAG, "=== StreamingSmoothness: backend=${backend.getName()} target=$targetTokens", sessionId = sessionId)

        return try {
            val request = InferenceRequest(
                taskId = UUID.randomUUID().toString(),
                taskType = TaskType.TEXT_GENERATION,
                modelId = model.id,
                inputs = listOf(ModalityInput.Text("请用 ${targetTokens} 个 token 解释什么是图灵测试。")),
                config = model.config.copy(maxTokens = targetTokens),
                stream = true,
                sessionId = sessionId
            )
            val intervals = mutableListOf<Long>()
            var prevTime = 0L
            val flow = backend.streamInfer(request, model)
            flow.collect { chunk ->
                val text = (chunk.partial as? ModalityOutput.Text)?.content.orEmpty()
                if (text.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    if (prevTime != 0L) intervals.add(now - prevTime)
                    prevTime = now
                }
                if (chunk.done) return@collect
            }
            computeSmoothness(intervals)
        } catch (t: Throwable) {
            LogSystem.error(TAG, "StreamingSmoothness threw: ${t.message}", t, sessionId = sessionId)
            SmoothnessResult(emptyList(), 0L, 0L, 0L, -1)
        } finally {
            LogSystem.endSession(sessionId, "StreamingSmoothness end")
        }
    }

    // ============== helpers ==============

    private suspend fun collectStream(
        backend: InferenceBackendV2,
        request: InferenceRequest,
        model: LoadedModel,
        sessionId: String,
        onText: (String) -> Unit
    ): Triple<Long, Long, Int> {
        val start = System.currentTimeMillis()
        var firstTokenTime = 0L
        var count = 0
        val flow = backend.streamInfer(request, model)
        flow.collect { chunk ->
            val text = (chunk.partial as? ModalityOutput.Text)?.content.orEmpty()
            if (text.isNotEmpty()) {
                if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                count++
                onText(text)
            }
        }
        val end = System.currentTimeMillis()
        val ttft = if (firstTokenTime == 0L) (end - start) else (firstTokenTime - start)
        return Triple(ttft, end - start, count)
    }

    private suspend fun measureFirstTokenAndTotal(
        backend: InferenceBackendV2,
        request: InferenceRequest,
        model: LoadedModel,
        sessionId: String
    ): Pair<Long, Long> {
        val start = System.currentTimeMillis()
        var firstTokenTime = 0L
        val flow = backend.streamInfer(request, model)
        flow.collect { chunk ->
            if (firstTokenTime == 0L && (chunk.partial as? ModalityOutput.Text)?.content.isNullOrEmpty() == false) {
                firstTokenTime = System.currentTimeMillis()
            }
        }
        val end = System.currentTimeMillis()
        val ttft = if (firstTokenTime == 0L) (end - start) else (firstTokenTime - start)
        return ttft to (end - start)
    }

    private fun buildPlaceholder(promptTokens: Int): String {
        val unit = "The quick brown fox jumps over the lazy dog. "
        val targetChars = (promptTokens * 4).coerceAtLeast(20)
        val sb = StringBuilder(targetChars + unit.length)
        while (sb.length < targetChars) sb.append(unit)
        return sb.toString()
    }

    private fun computeSmoothness(intervals: List<Long>): SmoothnessResult {
        if (intervals.isEmpty()) return SmoothnessResult(emptyList(), 0L, 0L, 0L, -1)
        val mean = intervals.average().toLong()
        val variance = intervals.map { (it - intervals.average()).let { d -> d * d } }.average().toLong()
        val maxInterval = intervals.maxOrNull() ?: 0L
        val maxPos = intervals.indexOf(maxInterval)
        return SmoothnessResult(
            tokenIntervals = intervals,
            meanIntervalMs = mean,
            varianceMs2 = variance,
            maxIntervalMs = maxInterval,
            maxIntervalPosition = maxPos
        )
    }
}

// ============== 高级基准结果类型 ==============

/** 冷启动结果：模型加载 + 首 token + 总响应时长。 */
data class ColdStartResult(
    val loadTimeMs: Long,
    val firstTokenMs: Long,
    val totalResponseMs: Long
)

/** 短消息聊天结果。 */
data class ChatResult(
    val ttftMs: Long,
    val tgTps: Double,
    val totalTokens: Int,
    val responseText: String
)

/** 多轮累积上下文结果：每轮一份指标。 */
data class MultiTurnResult(
    val ttftPerTurn: List<Long>,
    val tgTpsPerTurn: List<Double>,
    val contextGrowth: List<Int>
)

/** 长 prompt 处理结果。 */
data class LongPromptResult(
    val ppTps: Double,
    val ttftMs: Long,
    val promptTokens: Int
)

/** 流式平滑度结果。 */
data class SmoothnessResult(
    val tokenIntervals: List<Long>,
    val meanIntervalMs: Long,
    val varianceMs2: Long,
    val maxIntervalMs: Long,
    val maxIntervalPosition: Int
)
