package com.aibox.ui.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.db.BenchmarkResultEntity
import com.aibox.data.model.ModelInfo
import com.aibox.domain.inference.BackendDescriptor
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.InferenceBackendV2
import com.aibox.domain.inference.LoadedModel
import com.aibox.domain.inference.QuantType
import com.aibox.infra.benchmark.AdvancedBenchmark
import com.aibox.infra.benchmark.BackendComparison
import com.aibox.infra.benchmark.BenchmarkResult
import com.aibox.infra.benchmark.BenchmarkSpec
import com.aibox.infra.benchmark.ColdStartResult
import com.aibox.infra.benchmark.ComparisonProgress
import com.aibox.infra.benchmark.ComparisonReport
import com.aibox.infra.benchmark.MultiTurnResult
import com.aibox.infra.benchmark.SmoothnessResult
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 基准测试页 ViewModel（P2）。
 *
 * 职责：
 * 1. 维护"模型 + 后端 + 测试矩阵"选择状态
 * 2. 调用 [com.aibox.infra.benchmark.BenchmarkSystem] 跑 [BasicBenchmark] / [AdvancedBenchmark] / [BackendComparison]
 * 3. 把结果通过 [com.aibox.infra.benchmark.BenchmarkStorage] 持久化
 * 4. 暴露 [UiState.history] 给 UI 渲染历史结果列表
 *
 * 状态变更通过 [ui] StateFlow 推送；UI 用 collectAsStateWithLifecycle 收集。
 */
class BenchmarkViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val system get() = container.benchmarkSystem

    /** 测试矩阵类型。 */
    enum class Matrix {
        /** 基础三档负载（PP=64/32, PP=512/128, PP=1024/256）。 */
        BASIC,
        /** 线程扫描（1/2/4/6 线程，轻负载）。 */
        THREAD_SCAN,
        /** KV 量化扫描（F16 / Q8_0 / Q4_0，中负载）。 */
        KV_QUANT_SCAN,
        /** 跨后端对比（同模型 + 多后端，自动推荐最优）。 */
        COMPARISON,
        /** 高级：冷启动场景。 */
        COLD_START,
        /** 高级：多轮累积上下文场景。 */
        MULTI_TURN,
        /** 高级：流式平滑度。 */
        SMOOTHNESS
    }

    data class UiState(
        val models: List<ModelInfo> = emptyList(),
        val backends: List<BackendDescriptor> = emptyList(),
        val selectedModelPath: String? = null,
        val selectedBackendNames: Set<String> = emptySet(),
        val matrix: Matrix = Matrix.BASIC,
        val repCount: Int = 3,
        val warmup: Int = 1,
        val threadsOverride: Int? = null,
        val kvQuantOverride: QuantType? = null,
        val running: Boolean = false,
        val progress: ComparisonProgress? = null,
        val latestResults: List<BenchmarkResult> = emptyList(),
        val latestAdvanced: AdvancedResultBundle? = null,
        val latestReport: ComparisonReport? = null,
        val history: List<BenchmarkResultEntity> = emptyList(),
        val errorMessage: String? = null,
        val infoMessage: String? = null
    )

    /** 高级基准结果的打包，便于 UI 一次性展示。 */
    data class AdvancedResultBundle(
        val coldStart: ColdStartResult? = null,
        val multiTurn: MultiTurnResult? = null,
        val smoothness: SmoothnessResult? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // 观察可用模型
        viewModelScope.launch {
            container.modelRepository.observeAll().collectLatest { list ->
                _ui.value = _ui.value.copy(models = list)
            }
        }
        // 一次性加载已注册后端列表
        viewModelScope.launch {
            val list = container.backendManager.listBackends()
            _ui.value = _ui.value.copy(backends = list)
            // 默认全选
            if (_ui.value.selectedBackendNames.isEmpty() && list.isNotEmpty()) {
                _ui.value = _ui.value.copy(selectedBackendNames = list.map { it.getName() }.toSet())
            }
            if (list.isEmpty()) {
                _ui.value = _ui.value.copy(
                    infoMessage = "尚未注册任何 V2 后端；请先在设置页启用插件，或等待内置 V2 适配器（llama.cpp）"
                )
            }
        }
        // 观察基准测试结果历史
        viewModelScope.launch {
            system.storage.observeAll().collectLatest { history ->
                _ui.value = _ui.value.copy(history = history)
            }
        }
        // 顺手记录一次 baseline
        runCatching { system.logBaselineOnce() }
    }

    // ============== 选择 ==============

    fun selectModel(path: String?) {
        _ui.value = _ui.value.copy(selectedModelPath = path)
    }

    fun toggleBackend(name: String) {
        val cur = _ui.value.selectedBackendNames.toMutableSet()
        if (name in cur) cur.remove(name) else cur.add(name)
        _ui.value = _ui.value.copy(selectedBackendNames = cur)
    }

    fun selectMatrix(m: Matrix) {
        _ui.value = _ui.value.copy(matrix = m)
    }

    fun setRepCount(value: Int) {
        _ui.value = _ui.value.copy(repCount = value.coerceIn(1, 10))
    }

    fun setWarmup(value: Int) {
        _ui.value = _ui.value.copy(warmup = value.coerceIn(0, 5))
    }

    fun setThreadsOverride(value: Int?) {
        _ui.value = _ui.value.copy(threadsOverride = value)
    }

    fun setKvQuantOverride(value: QuantType?) {
        _ui.value = _ui.value.copy(kvQuantOverride = value)
    }

    fun dismissInfo() {
        _ui.value = _ui.value.copy(infoMessage = null)
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(errorMessage = null)
    }

    // ============== 运行 ==============

    /**
     * 根据当前选择启动基准测试。
     *
     * - [Matrix.BASIC] / [Matrix.THREAD_SCAN] / [Matrix.KV_QUANT_SCAN]：
     *   对每个选中的后端跑基础矩阵，结果存历史。
     * - [Matrix.COMPARISON]：跑 [BackendComparison.compareBackends]，自动推荐最优后端。
     * - [Matrix.COLD_START] / [Matrix.MULTI_TURN] / [Matrix.SMOOTHNESS]：跑对应 [AdvancedBenchmark]。
     */
    fun run() {
        if (_ui.value.running) return
        val modelPath = _ui.value.selectedModelPath ?: run {
            _ui.value = _ui.value.copy(errorMessage = "请先选择一个模型")
            return
        }
        if (_ui.value.selectedBackendNames.isEmpty()) {
            _ui.value = _ui.value.copy(errorMessage = "请至少选择一个后端")
            return
        }

        _ui.value = _ui.value.copy(
            running = true,
            progress = null,
            latestResults = emptyList(),
            latestAdvanced = null,
            latestReport = null,
            errorMessage = null,
            infoMessage = null
        )
        system.setRunning(true)

        viewModelScope.launch {
            try {
                when (_ui.value.matrix) {
                    Matrix.BASIC, Matrix.THREAD_SCAN, Matrix.KV_QUANT_SCAN -> runBasicMatrix(modelPath)
                    Matrix.COMPARISON -> runComparison(modelPath)
                    Matrix.COLD_START -> runColdStart(modelPath)
                    Matrix.MULTI_TURN -> runMultiTurn(modelPath)
                    Matrix.SMOOTHNESS -> runSmoothness(modelPath)
                }
            } catch (t: Throwable) {
                LogSystem.error("aibox.ui.benchmark", "run() threw: ${t.message}", t)
                _ui.value = _ui.value.copy(errorMessage = "运行失败: ${t.message}")
            } finally {
                system.setRunning(false)
                _ui.value = _ui.value.copy(running = false, progress = null)
            }
        }
    }

    /** 跑基础矩阵（BASIC / THREAD_SCAN / KV_QUANT_SCAN），对每个选中后端跑一组 spec。 */
    private suspend fun runBasicMatrix(modelPath: String) {
        val backends = _ui.value.selectedBackendNames
        val baseConfig = buildConfig()
        val repCount = _ui.value.repCount
        val warmup = _ui.value.warmup

        val specs: List<BenchmarkSpec> = when (_ui.value.matrix) {
            Matrix.BASIC -> listOf(
                BenchmarkSpec(modelPath, baseConfig.copy(maxTokens = 32),  ppSize = 64,   tgSize = 32,  repCount = repCount, warmup = warmup),
                BenchmarkSpec(modelPath, baseConfig.copy(maxTokens = 128), ppSize = 512,  tgSize = 128, repCount = repCount, warmup = warmup),
                BenchmarkSpec(modelPath, baseConfig.copy(maxTokens = 256), ppSize = 1024, tgSize = 256, repCount = repCount, warmup = warmup)
            )
            Matrix.THREAD_SCAN -> listOf(1, 2, 4, 6).map { t ->
                BenchmarkSpec(modelPath, baseConfig.copy(threads = t, maxTokens = 32), ppSize = 64, tgSize = 32, repCount = repCount, warmup = warmup)
            }
            Matrix.KV_QUANT_SCAN -> listOf(QuantType.F16, QuantType.Q8_0, QuantType.Q4_0).map { q ->
                BenchmarkSpec(modelPath, baseConfig.copy(kvQuant = q, maxTokens = 128), ppSize = 512, tgSize = 128, repCount = repCount, warmup = warmup)
            }
            else -> emptyList()
        }
        if (specs.isEmpty()) return

        val allResults = mutableListOf<BenchmarkResult>()
        val totalTasks = backends.size * specs.size
        var done = 0
        _ui.value = _ui.value.copy(
            progress = ComparisonProgress(total = totalTasks, completed = done, current = null, results = emptyMap())
        )

        for (backendName in backends) {
            val backend = container.backendManager.getBackend(backendName) ?: continue
            var loaded: LoadedModel? = null
            try {
                val loadOutcome = backend.loadModel(modelPath, baseConfig)
                loaded = loadOutcome.getOrNull()
                if (loaded == null) {
                    val reason = loadOutcome.reasonOrNull() ?: "unknown"
                    LogSystem.error("aibox.ui.benchmark", "loadModel failed for $backendName: $reason")
                    specs.forEach { spec ->
                        val failed = BenchmarkResult.failed(spec, "loadModel: $reason")
                        allResults.add(failed)
                        system.storage.save(failed, backendName, modelFormat = null, descriptor = container.backendManager.getDescriptor(backendName))
                    }
                    continue
                }
                for (spec in specs) {
                    _ui.value = _ui.value.copy(
                        progress = _ui.value.progress?.copy(
                            current = "$backendName · pp=${spec.ppSize} tg=${spec.tgSize}" +
                                (spec.config.threads?.let { " threads=$it" } ?: "") +
                                (if (spec.config.kvQuant != baseConfig.kvQuant) " kv=${spec.config.kvQuant}" else ""),
                            completed = done
                        )
                    )
                    val r = system.basicBenchmark.run(backend, loaded, spec)
                    allResults.add(r)
                    system.storage.save(r, backendName, modelFormat = null, descriptor = container.backendManager.getDescriptor(backendName))
                    done++
                    _ui.value = _ui.value.copy(
                        latestResults = allResults.toList(),
                        progress = _ui.value.progress?.copy(completed = done)
                    )
                }
            } finally {
                loaded?.let { runCatching { backend.unload(it) } }
            }
        }

        _ui.value = _ui.value.copy(
            latestResults = allResults.toList(),
            infoMessage = "完成：${allResults.size} 项（${allResults.count { !it.failed }} 成功，${allResults.count { it.failed }} 失败）"
        )
    }

    /** 跑跨后端对比。 */
    private suspend fun runComparison(modelPath: String) {
        val backends = _ui.value.selectedBackendNames.toList()
        val baseConfig = buildConfig()
        // 监听 BackendComparison.progress
        val progressJob = viewModelScope.launch {
            system.backendComparison.progress.collectLatest { p ->
                _ui.value = _ui.value.copy(progress = p)
            }
        }
        try {
            val report = system.backendComparison.compareBackends(
                modelPath = modelPath,
                modelFormat = inferModelFormat(modelPath),
                backends = backends,
                baseConfig = baseConfig
            )
            // 持久化每条结果
            report.resultsByBackend.forEach { (name, r) ->
                system.storage.save(r, name, modelFormat = report.modelFormat, descriptor = container.backendManager.getDescriptor(name))
            }
            _ui.value = _ui.value.copy(
                latestReport = report,
                latestResults = report.resultsByBackend.values.toList(),
                infoMessage = report.recommendation
            )
        } finally {
            progressJob.cancel()
        }
    }

    /** 跑冷启动场景。 */
    private suspend fun runColdStart(modelPath: String) {
        val backendName = _ui.value.selectedBackendNames.firstOrNull() ?: return
        val backend = container.backendManager.getBackend(backendName) ?: return
        val result = AdvancedBenchmark.coldStart(backend, modelPath, buildConfig())
        _ui.value = _ui.value.copy(
            latestAdvanced = AdvancedResultBundle(coldStart = result),
            infoMessage = "冷启动：加载 ${result.loadTimeMs}ms · 首 token ${result.firstTokenMs}ms · 总响应 ${result.totalResponseMs}ms"
        )
    }

    /** 跑多轮累积上下文场景。 */
    private suspend fun runMultiTurn(modelPath: String) {
        val backendName = _ui.value.selectedBackendNames.firstOrNull() ?: return
        val backend = container.backendManager.getBackend(backendName) ?: return
        val config = buildConfig()
        val loaded = backend.loadModel(modelPath, config).getOrNull() ?: run {
            _ui.value = _ui.value.copy(errorMessage = "loadModel 失败")
            return
        }
        try {
            val result = AdvancedBenchmark.multiTurn(backend, loaded, turns = 10)
            _ui.value = _ui.value.copy(
                latestAdvanced = AdvancedResultBundle(multiTurn = result),
                infoMessage = "多轮 ${result.ttftPerTurn.size} 轮完成；平均 TTFT ${if (result.ttftPerTurn.isEmpty()) 0L else result.ttftPerTurn.average().toLong()}ms"
            )
        } finally {
            runCatching { backend.unload(loaded) }
        }
    }

    /** 跑流式平滑度。 */
    private suspend fun runSmoothness(modelPath: String) {
        val backendName = _ui.value.selectedBackendNames.firstOrNull() ?: return
        val backend = container.backendManager.getBackend(backendName) ?: return
        val config = buildConfig()
        val loaded = backend.loadModel(modelPath, config).getOrNull() ?: run {
            _ui.value = _ui.value.copy(errorMessage = "loadModel 失败")
            return
        }
        try {
            val result = AdvancedBenchmark.streamingSmoothness(backend, loaded, targetTokens = 100)
            _ui.value = _ui.value.copy(
                latestAdvanced = AdvancedResultBundle(smoothness = result),
                infoMessage = "平滑度：间隔均值 ${result.meanIntervalMs}ms · 方差 ${result.varianceMs2} · 最大 ${result.maxIntervalMs}ms @ token #${result.maxIntervalPosition}"
            )
        } finally {
            runCatching { backend.unload(loaded) }
        }
    }

    // ============== 历史 ==============

    fun clearHistory() {
        viewModelScope.launch {
            val ok = system.storage.deleteAll()
            _ui.value = _ui.value.copy(
                infoMessage = if (ok) "历史已清空" else "清空失败"
            )
        }
    }

    // ============== helpers ==============

    private fun buildConfig(): InferenceConfigV2 {
        val base = InferenceConfigV2()
        return base.copy(
            threads = _ui.value.threadsOverride ?: base.threads,
            kvQuant = _ui.value.kvQuantOverride ?: base.kvQuant
        )
    }

    /** 根据模型文件后缀粗估模型格式（用于 BackendComparison 的日志与推荐语）。 */
    private fun inferModelFormat(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "gguf" -> "gguf"
            "tflite" -> "tflite"
            "mlc", "mlmodel" -> "mlc"
            "mnn" -> "mnn"
            else -> "unknown"
        }
    }

    @Suppress("unused")
    private fun backendOrLog(name: String): InferenceBackendV2? {
        val b = container.backendManager.getBackend(name)
        if (b == null) LogSystem.warn("aibox.ui.benchmark", "backend $name not registered")
        return b
    }
}
