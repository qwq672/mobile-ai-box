package com.aibox.ui.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.db.BenchmarkResultEntity
import com.aibox.data.model.ModelInfo
import com.aibox.domain.inference.BackendDescriptor
import com.aibox.domain.inference.InferenceConfig
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.LoadedModel
import com.aibox.domain.inference.QuantType
import com.aibox.infra.benchmark.BenchmarkResult
import com.aibox.infra.benchmark.BenchmarkSpec
import com.aibox.infra.benchmark.ComparisonProgress
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 基准测试页 ViewModel。
 *
 * 定位：**最基础的单次测量**——选一个模型、一个后端，用自定义 PP / TG 跑一次，
 * 得到当前配置下的 PP t/s、TG t/s、TTFT、内存、温度、电量等数字。
 *
 * 不做场景矩阵、不做跨后端对比、不做配置扫描。需要"对比 + 选最优 + 写回设置"
 * 请用自动调优（[com.aibox.ui.autotune.AutoTuneViewModel]）。
 */
class BenchmarkViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val system get() = container.benchmarkSystem

    data class UiState(
        val models: List<ModelInfo> = emptyList(),
        val backends: List<BackendDescriptor> = emptyList(),
        val selectedModelPath: String? = null,
        val selectedBackendNames: Set<String> = emptySet(),
        /** 提示词长度（近似 token 数）。 */
        val ppSize: Int = DEFAULT_PP,
        /** 生成 token 数。 */
        val tgSize: Int = DEFAULT_TG,
        /** 重复次数：1 次即可；想更稳可以调到 2~3。 */
        val repCount: Int = 1,
        /** 预热次数，不计入统计。 */
        val warmup: Int = 1,
        val running: Boolean = false,
        val progress: ComparisonProgress? = null,
        val latestResults: List<BenchmarkResult> = emptyList(),
        val history: List<BenchmarkResultEntity> = emptyList(),
        val errorMessage: String? = null,
        val infoMessage: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            container.modelRepository.observeAll().collectLatest { list ->
                _ui.value = _ui.value.copy(models = list)
            }
        }
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { container.pluginManager.syncInstalledBackendPlugins() }
                    .onFailure { LogSystem.warn(TAG, "sync plugins failed: ${it.message}", it) }
                container.backendManager.listBackends()
            }
            _ui.value = _ui.value.copy(
                backends = list,
                selectedBackendNames = _ui.value.selectedBackendNames.ifEmpty {
                    list.map { it.getName() }.toSet()
                }
            )
            if (list.isEmpty()) {
                _ui.value = _ui.value.copy(infoMessage = "尚未注册任何 V2 后端，请先在设置页启用插件")
            }
        }
        viewModelScope.launch {
            system.storage.observeAll().collectLatest { history ->
                _ui.value = _ui.value.copy(history = history)
            }
        }
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

    fun setPpSize(value: Int) {
        _ui.value = _ui.value.copy(ppSize = value.coerceIn(32, 4096))
    }

    fun setTgSize(value: Int) {
        _ui.value = _ui.value.copy(tgSize = value.coerceIn(8, 1024))
    }

    fun setRepCount(value: Int) {
        _ui.value = _ui.value.copy(repCount = value.coerceIn(1, 3))
    }

    fun setWarmup(value: Int) {
        _ui.value = _ui.value.copy(warmup = value.coerceIn(0, 2))
    }

    fun dismissInfo() {
        _ui.value = _ui.value.copy(infoMessage = null)
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(errorMessage = null)
    }

    // ============== 运行 ==============

    /**
     * 跑一次基础基准：每个选中的后端各加载一次模型，用当前 PP/TG 测一次。
     * 进度会先显示「加载模型」，避免长时间停在「准备中」。
     */
    fun run() {
        if (_ui.value.running) return
        val modelPath = _ui.value.selectedModelPath ?: run {
            _ui.value = _ui.value.copy(errorMessage = "请先选择一个模型")
            return
        }
        val backendNames = _ui.value.selectedBackendNames.toList()
        if (backendNames.isEmpty()) {
            _ui.value = _ui.value.copy(errorMessage = "请至少选择一个后端")
            return
        }

        val pp = _ui.value.ppSize
        val tg = _ui.value.tgSize
        val reps = _ui.value.repCount
        val warmup = _ui.value.warmup

        _ui.value = _ui.value.copy(
            running = true,
            latestResults = emptyList(),
            errorMessage = null,
            infoMessage = null,
            progress = ComparisonProgress(
                total = backendNames.size,
                completed = 0,
                current = "准备加载 ${backendNames.first()}…"
            )
        )
        system.setRunning(true)

        viewModelScope.launch {
            try {
                val baseConfig = buildConfig()
                val results = mutableListOf<BenchmarkResult>()
                var done = 0
                for (name in backendNames) {
                    val backend = container.backendManager.getBackend(name)
                    if (backend == null) {
                        done++
                        _ui.value = _ui.value.copy(
                            progress = ComparisonProgress(backendNames.size, done, "$name 未注册，跳过")
                        )
                        continue
                    }
                    _ui.value = _ui.value.copy(
                        progress = ComparisonProgress(
                            total = backendNames.size,
                            completed = done,
                            current = "$name · 加载模型（大模型可能需要十几秒）…"
                        )
                    )
                    var loaded: LoadedModel? = null
                    try {
                        val loadOutcome = withContext(Dispatchers.IO) { backend.loadModel(modelPath, baseConfig) }
                        loaded = loadOutcome.getOrNull()
                        if (loaded == null) {
                            val reason = loadOutcome.reasonOrNull() ?: "unknown"
                            val spec = BenchmarkSpec(modelPath, baseConfig, pp, tg, reps, warmup)
                            val failed = BenchmarkResult.failed(spec, "loadModel: $reason")
                            results += failed
                            system.storage.save(
                                failed, name, modelFormat = null,
                                descriptor = container.backendManager.getDescriptor(name)
                            )
                            done++
                            _ui.value = _ui.value.copy(
                                latestResults = results.toList(),
                                progress = ComparisonProgress(backendNames.size, done, "$name · 加载失败")
                            )
                            continue
                        }
                        _ui.value = _ui.value.copy(
                            progress = ComparisonProgress(
                                total = backendNames.size,
                                completed = done,
                                current = "$name · 测试 PP=$pp TG=$tg（${if (reps > 1) "重复 $reps 次" else "单次"}）…"
                            )
                        )
                        val spec = BenchmarkSpec(
                            modelPath = modelPath,
                            config = baseConfig.copy(maxTokens = tg),
                            ppSize = pp,
                            tgSize = tg,
                            repCount = reps,
                            warmup = warmup
                        )
                        val r = withContext(Dispatchers.IO) { system.basicBenchmark.run(backend, loaded, spec) }
                        results += r
                        system.storage.save(
                            r, name, modelFormat = null,
                            descriptor = container.backendManager.getDescriptor(name)
                        )
                    } finally {
                        loaded?.let { handle -> withContext(Dispatchers.IO) { runCatching { backend.unload(handle) } } }
                    }
                    done++
                    _ui.value = _ui.value.copy(
                        latestResults = results.toList(),
                        progress = ComparisonProgress(backendNames.size, done, "$name · 完成")
                    )
                }

                _ui.value = _ui.value.copy(
                    latestResults = results.toList(),
                    infoMessage = "完成：${results.size} 项（${results.count { !it.failed }} 成功，${results.count { it.failed }} 失败）"
                )
            } catch (t: Throwable) {
                LogSystem.error(TAG, "run() threw: ${t.message}", t)
                _ui.value = _ui.value.copy(errorMessage = "运行失败: ${t.message}")
            } finally {
                system.setRunning(false)
                _ui.value = _ui.value.copy(running = false, progress = null)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val ok = system.storage.deleteAll()
            _ui.value = _ui.value.copy(infoMessage = if (ok) "历史已清空" else "清空失败")
        }
    }

    // ============== helpers ==============

    /** 读取「设置 → 推理」当前生效配置；基准测试不覆盖任何参数。 */
    private suspend fun buildConfig(): InferenceConfigV2 {
        val cfg = withContext(Dispatchers.IO) {
            container.settingsRepository.config.firstOrNull()
        } ?: InferenceConfig()
        val kv = when (cfg.kvQuant.lowercase()) {
            "f16" -> QuantType.F16
            "q4_0" -> QuantType.Q4_0
            else -> QuantType.Q8_0
        }
        return InferenceConfigV2().copy(
            threads = cfg.cpuThreads.takeIf { it > 0 },
            batchSize = cfg.physicalBatch,
            contextLength = cfg.contextLength,
            kvQuant = kv
        )
    }

    companion object {
        private const val TAG = "aibox.ui.benchmark"

        /** 默认 PP：约 512 token，接近一段带历史的真实提问。 */
        const val DEFAULT_PP = 512

        /** 默认 TG：64 token，足够看出 decode 速度，又不会等太久。 */
        const val DEFAULT_TG = 64
    }
}
