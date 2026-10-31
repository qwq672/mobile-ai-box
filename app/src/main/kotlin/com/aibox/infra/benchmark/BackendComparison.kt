package com.aibox.infra.benchmark

import com.aibox.domain.inference.BackendManagerV2
import com.aibox.domain.inference.InferenceBackendV2
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.LoadedModel
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Layer 3 跨后端对比：用同一份模型文件跑多个后端，比较 [BenchmarkResult]。
 *
 * 用户在 Benchmark UI 选定模型 + 多个后端，点击"开始对比"，[BackendComparison] 依次：
 * 1. 对每个后端调 [InferenceBackendV2.loadModel]。
 * 2. 用同一份 [BenchmarkSpec]（默认轻负载 PP=64 TG=32）跑 [BasicBenchmark.run]。
 * 3. 收集所有 [BenchmarkResult]，找出 TG t/s 最高的作为 [ComparisonReport.recommendedBackend]。
 * 4. 生成"人话版"推荐语（如 "推荐 llama.cpp：TG 35 t/s，比 litert 快 22%"）。
 *
 * 进度通过 [progress] StateFlow 暴露给 UI 实时显示。
 *
 * @param backendManager 已注册的后端管理器
 * @param basicBenchmark 基础基准执行器
 * @param advancedBenchmark 高级基准执行器（当前 compareBackends 只用 basic；预留高级对比）
 */
class BackendComparison(
    private val backendManager: BackendManagerV2,
    private val basicBenchmark: BasicBenchmark,
    @Suppress("unused") private val advancedBenchmark: AdvancedBenchmark
) {

    private val tag = "aibox.benchmark.compare"

    private val _progress = MutableStateFlow(ComparisonProgress())
    val progress: StateFlow<ComparisonProgress> = _progress.asStateFlow()

    /**
     * 同模型 × 多后端对比。
     *
     * @param modelPath 模型文件绝对路径
     * @param modelFormat 模型格式字符串（如 "gguf" / "tflite"），用于日志与排序提示
     * @param backends 要对比的后端名称列表；默认用 [backendManager.listBackends] 全部
     * @param baseConfig 基线配置；用于 loadModel
     * @param spec 测试规格；默认 PP=64 TG=32 repCount=3（轻负载快速对比）
     */
    suspend fun compareBackends(
        modelPath: String,
        modelFormat: String,
        backends: List<String> = backendManager.listBackends().map { it.getName() },
        baseConfig: InferenceConfigV2 = InferenceConfigV2(),
        spec: BenchmarkSpec = BenchmarkSpec(
            modelPath = modelPath,
            config = baseConfig,
            ppSize = 64,
            tgSize = 32,
            repCount = 3
        )
    ): ComparisonReport {
        val sessionId = LogSystem.startSession("benchmark.compare", modelPath.substringAfterLast('/'))
        LogSystem.info(tag, "=== CompareBackends start: model=$modelPath format=$modelFormat backends=$backends", sessionId = sessionId)

        _progress.value = ComparisonProgress(total = backends.size, completed = 0, current = null, results = emptyMap())

        val results = linkedMapOf<String, BenchmarkResult>()

        for ((idx, name) in backends.withIndex()) {
            _progress.value = _progress.value.copy(
                current = name,
                completed = idx,
                results = results.toMap()
            )
            LogSystem.info(tag, "compareBackends[$idx/${backends.size}] $name start", sessionId = sessionId)

            val backend = backendManager.getBackend(name)
            if (backend == null) {
                LogSystem.warn(tag, "backend $name not registered; skipping", sessionId = sessionId)
                results[name] = BenchmarkResult.failed(spec, "backend $name not registered")
                continue
            }

            // 加载模型
            var loaded: LoadedModel? = null
            try {
                val loadOutcome = backend.loadModel(modelPath, baseConfig)
                loaded = loadOutcome.getOrNull()
                if (loaded == null) {
                    val reason = loadOutcome.reasonOrNull() ?: "unknown"
                    LogSystem.error(tag, "compareBackends[$name] loadModel failed: $reason", sessionId = sessionId)
                    results[name] = BenchmarkResult.failed(spec, "loadModel: $reason")
                    continue
                }
                // 跑基础基准
                val result = basicBenchmark.run(backend, loaded, spec)
                results[name] = result
                LogSystem.info(tag, "compareBackends[$name] done: tgTps=${"%.2f".format(result.tgTps)} ppTps=${"%.2f".format(result.ppTps)} failed=${result.failed}", sessionId = sessionId)
            } catch (t: Throwable) {
                LogSystem.error(tag, "compareBackends[$name] threw: ${t.message}", t, sessionId = sessionId)
                results[name] = BenchmarkResult.failed(spec, t.message ?: t.javaClass.simpleName)
            } finally {
                loaded?.let { runCatching { backend.unload(it) } }
            }

            _progress.value = _progress.value.copy(
                completed = idx + 1,
                results = results.toMap()
            )
        }

        _progress.value = _progress.value.copy(current = null, results = results.toMap())
        LogSystem.endSession(sessionId, "CompareBackends end: ${results.size}/${backends.size} succeeded")

        return ComparisonReport.from(
            modelPath = modelPath,
            resultsByBackend = results.toMap(),
            modelFormat = modelFormat
        )
    }
}

/**
 * 跨后端对比进度，供 UI 实时显示。
 *
 * @property total 要对比的后端总数
 * @property completed 已完成数（含失败）
 * @property current 当前正在跑的后端名；null = 空闲 / 完成
 * @property results 至今为止每个后端的 [BenchmarkResult]
 */
data class ComparisonProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val current: String? = null,
    val results: Map<String, BenchmarkResult> = emptyMap()
)

/**
 * 跨后端对比报告。
 *
 * @property modelPath 模型路径
 * @property modelFormat 模型格式（如 "gguf" / "tflite"），用于推荐语上下文
 * @property resultsByBackend 各后端的 [BenchmarkResult]；含失败项（[BenchmarkResult.failed] = true）
 * @property recommendedBackend 推荐后端名（基于 TG t/s 最优；若全部失败则为空字符串）
 * @property recommendation "人话版"推荐语
 */
data class ComparisonReport(
    val modelPath: String,
    val modelFormat: String,
    val resultsByBackend: Map<String, BenchmarkResult>,
    val recommendedBackend: String,
    val recommendation: String
) {
    companion object {
        /**
         * 从 resultsByBackend 自动选出 [recommendedBackend] 并生成 [recommendation] 文案。
         *
         * 选择规则：
         * 1. 过滤 [BenchmarkResult.failed] = true 的项。
         * 2. 在剩余项中按 [BenchmarkResult.tgTps] 降序排，取第一名。
         * 3. 若全部失败：recommendedBackend = ""，recommendation = "全部后端均失败，请检查日志"。
         * 4. 若只剩一个：直接推荐它，附上 tgTps。
         * 5. 若有多个：推荐第一名 + "比第二名快 X%"。
         */
        fun from(
            modelPath: String,
            resultsByBackend: Map<String, BenchmarkResult>,
            modelFormat: String = "gguf"
        ): ComparisonReport {
            val succeeded = resultsByBackend.filter { !it.value.failed }
            if (succeeded.isEmpty()) {
                return ComparisonReport(
                    modelPath = modelPath,
                    modelFormat = modelFormat,
                    resultsByBackend = resultsByBackend,
                    recommendedBackend = "",
                    recommendation = "全部后端均失败，请检查日志（路径见 benchmark session 文件）"
                )
            }
            // 排序：按 TG t/s 降序
            val sorted = succeeded.toList().sortedByDescending { it.second.tgTps }
            val best = sorted.first()
            if (sorted.size == 1) {
                val r = best.second
                return ComparisonReport(
                    modelPath = modelPath,
                    modelFormat = modelFormat,
                    resultsByBackend = resultsByBackend,
                    recommendedBackend = best.first,
                    recommendation = "推荐 ${best.first}：TG ${"%.2f".format(r.tgTps)} t/s, PP ${"%.2f".format(r.ppTps)} t/s, TTFT ${r.ttftMs}ms"
                )
            }
            // 多个：算出第二名，给百分比
            val second = sorted[1]
            val bestTps = best.second.tgTps
            val secondTps = second.second.tgTps
            val pct = if (secondTps > 0) ((bestTps - secondTps) * 100.0 / secondTps) else 0.0
            val r = best.second
            return ComparisonReport(
                modelPath = modelPath,
                modelFormat = modelFormat,
                resultsByBackend = resultsByBackend,
                recommendedBackend = best.first,
                recommendation = "推荐 ${best.first}：TG ${"%.2f".format(bestTps)} t/s, PP ${"%.2f".format(r.ppTps)} t/s, TTFT ${r.ttftMs}ms（比第二名 ${second.first} 快 ${"%.1f".format(pct)}%）"
            )
        }
    }
}
