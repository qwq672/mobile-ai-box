package com.aibox.ui.autotune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.model.ModelInfo
import com.aibox.domain.inference.BackendDescriptor
import com.aibox.domain.inference.InferenceConfig
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.QuantType
import com.aibox.infra.benchmark.BenchmarkResult
import com.aibox.infra.benchmark.BenchmarkSpec
import com.aibox.infra.benchmark.ThermalState
import com.aibox.util.CpuInfo
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自动调优 ViewModel。
 *
 * 与基准测试的分工：
 * - 基准测试：跑一次、给数字，不改任何设置；
 * - 自动调优：把「后端 × 线程 × KV × batch」展开成明确的步骤列表，逐项实测，
 *   同时记录速度、TTFT、温度、电量下降、内存、CPU 占用，综合打分后写回最优配置。
 *
 * 关键点：所有步骤在开始前就算好，进度以「第 i/N 步 + 当前动作」输出，
 * 不会出现长时间停在「准备中」的情况。
 */
class AutoTuneViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val system get() = container.benchmarkSystem

    enum class Level(val label: String, val desc: String) {
        INSTANT("即时", "线程两档，轻负载单次；几十秒内给出结果"),
        LOW("低", "线程两档 + 当前 KV，轻负载，重复 2 次"),
        MEDIUM("中", "线程三档 × KV 两档，中等负载"),
        HIGH("高", "线程四档 × KV 两档 × batch 两档；耗时较长"),
        EXTREME("极致", "线程四档 × KV 三档 × batch 两档；最全面也最慢")
    }

    /** 单个候选配置的实测结果（含环境指标）。 */
    data class CandidateResult(
        val backend: String,
        val threads: Int?,
        val kv: QuantType,
        val batch: Int,
        val context: Int,
        val ppTps: Double,
        val tgTps: Double,
        val ttftMs: Long,
        val thermal: String,
        val batteryDelta: Int,
        val memPercent: Int,
        val cpuPercent: Int,
        val energyMj: Long?,
        val score: Double,
        val failed: Boolean,
        val note: String
    )

    data class UiState(
        val models: List<ModelInfo> = emptyList(),
        val backends: List<BackendDescriptor> = emptyList(),
        val selectedModelPath: String? = null,
        val selectedBackendNames: Set<String> = emptySet(),
        val level: Level = Level.MEDIUM,
        val hotTest: Boolean = false,
        val running: Boolean = false,
        val progressText: String = "",
        val progress: Float = 0f,
        /** 当前第几步（1 开始，0 表示未开始）。 */
        val stepIndex: Int = 0,
        /** 本次计划总步数，开始前就已知。 */
        val stepTotal: Int = 0,
        val elapsedMs: Long = 0L,
        val results: List<CandidateResult> = emptyList(),
        val best: CandidateResult? = null,
        val summary: String? = null,
        val errorMessage: String? = null,
        val infoMessage: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var timerJob: Job? = null

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
        }
    }

    fun selectModel(path: String?) {
        _ui.value = _ui.value.copy(selectedModelPath = path)
    }

    fun toggleBackend(name: String) {
        val cur = _ui.value.selectedBackendNames.toMutableSet()
        if (name in cur) cur.remove(name) else cur.add(name)
        _ui.value = _ui.value.copy(selectedBackendNames = cur)
    }

    fun selectLevel(level: Level) {
        if (_ui.value.running) return
        _ui.value = _ui.value.copy(level = level)
    }

    fun setHotTest(enabled: Boolean) {
        if (_ui.value.running) return
        _ui.value = _ui.value.copy(hotTest = enabled)
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(errorMessage = null)
    }

    fun dismissInfo() {
        _ui.value = _ui.value.copy(infoMessage = null)
    }

    /** 计划步数预览（UI 在开始前显示，让用户知道会跑多少项）。 */
    fun plannedSteps(): Int {
        val cfg = buildCandidates(_ui.value.level, previewBaseConfig()).size
        val backends = _ui.value.selectedBackendNames.size.coerceAtLeast(1)
        val heat = if (_ui.value.hotTest && _ui.value.level.ordinal >= Level.HIGH.ordinal) 1 else 0
        return cfg * backends + heat
    }

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

        val level = _ui.value.level

        _ui.value = _ui.value.copy(
            running = true,
            progress = 0f,
            stepIndex = 0,
            stepTotal = 0,
            elapsedMs = 0L,
            progressText = "读取当前推理配置…",
            results = emptyList(),
            best = null,
            summary = null,
            errorMessage = null,
            infoMessage = null
        )
        system.setRunning(true)

        val startedAt = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_ui.value.running) {
                delay(1000)
                if (_ui.value.running) {
                    _ui.value = _ui.value.copy(elapsedMs = System.currentTimeMillis() - startedAt)
                }
            }
        }

        viewModelScope.launch {
            var step = 0
            try {
                // 真实基线：线程/KV/上下文/批处理取「设置 → 推理」当前值
                val base = currentConfig()
                // 候选必须在读到真实基线后再展开，否则 KV 扫描档位会和界面显示不一致
                val candidates = buildCandidates(level, base)
                val heatSteps = if (_ui.value.hotTest && level.ordinal >= Level.HIGH.ordinal) 1 else 0
                val totalSteps = candidates.size * backendNames.size + heatSteps
                _ui.value = _ui.value.copy(
                    stepTotal = totalSteps,
                    progressText = "共 $totalSteps 步：准备加载 ${backendNames.first()}…"
                )
                val collected = mutableListOf<Pair<CandidateResult, BenchmarkResult>>()

                // 高温预载（可选）：算作第 1 步，避免用户不知道在干什么
                if (heatSteps > 0) {
                    step++
                    _ui.value = _ui.value.copy(
                        stepIndex = step,
                        progress = step.toFloat() / totalSteps,
                        progressText = "第 $step/$totalSteps 步 · 预热设备（持续负载约 1 分钟）…"
                    )
                    backendNames.firstOrNull()?.let { name ->
                        container.backendManager.getBackend(name)?.let { backend ->
                            val heatSpec = BenchmarkSpec(
                                modelPath,
                                base.copy(maxTokens = 256),
                                ppSize = 1024, tgSize = 256, repCount = 1, warmup = 0
                            )
                            withContext(Dispatchers.IO) {
                                backend.loadModel(modelPath, heatSpec.config).getOrNull()?.let { loaded ->
                                    try {
                                        system.basicBenchmark.run(backend, loaded, heatSpec)
                                    } finally {
                                        runCatching { backend.unload(loaded) }
                                    }
                                }
                            }
                        }
                    }
                }

                for (backendName in backendNames) {
                    val backend = container.backendManager.getBackend(backendName)
                    if (backend == null) {
                        step += candidates.size
                        _ui.value = _ui.value.copy(
                            stepIndex = step,
                            progress = step.toFloat() / totalSteps,
                            progressText = "跳过未注册的后端 $backendName"
                        )
                        continue
                    }
                    for (candidate in candidates) {
                        step++
                        val label = "${backendName} · 线程 ${candidate.threads ?: "自动"} · KV ${candidate.kv} · batch ${candidate.batch}"
                        _ui.value = _ui.value.copy(
                            stepIndex = step,
                            progress = (step - 1).toFloat() / totalSteps,
                            progressText = "第 $step/$totalSteps 步 · 加载模型 · $label"
                        )
                        LogSystem.info(TAG, "step $step/$totalSteps load: $label")

                        val spec = BenchmarkSpec(
                            modelPath = modelPath,
                            config = base.copy(
                                threads = candidate.threads,
                                kvQuant = candidate.kv,
                                batchSize = candidate.batch,
                                contextLength = candidate.context,
                                maxTokens = candidate.tgSize
                            ),
                            ppSize = candidate.ppSize,
                            tgSize = candidate.tgSize,
                            repCount = candidate.repCount,
                            warmup = candidate.warmup
                        )

                        val envBefore = runCatching { system.environmentMonitor.snapshot() }.getOrNull()
                        val load = withContext(Dispatchers.IO) { backend.loadModel(modelPath, spec.config) }
                        val loaded = load.getOrNull()
                        val raw: BenchmarkResult
                        if (loaded == null) {
                            raw = BenchmarkResult.failed(spec, load.reasonOrNull() ?: "loadModel failed")
                        } else {
                            _ui.value = _ui.value.copy(
                                progressText = "第 $step/$totalSteps 步 · 测试中 PP=${spec.ppSize} TG=${spec.tgSize} · $label"
                            )
                            raw = try {
                                withContext(Dispatchers.IO) {
                                    system.basicBenchmark.run(backend, loaded, spec)
                                }
                            } catch (t: Throwable) {
                                BenchmarkResult.failed(spec, t.message ?: t::class.java.simpleName)
                            } finally {
                                withContext(Dispatchers.IO) { runCatching { backend.unload(loaded) } }
                            }
                        }
                        val envAfter = runCatching { system.environmentMonitor.snapshot() }.getOrNull()

                        val row = CandidateResult(
                            backend = backendName,
                            threads = candidate.threads,
                            kv = candidate.kv,
                            batch = candidate.batch,
                            context = candidate.context,
                            ppTps = raw.ppTps,
                            tgTps = raw.tgTps,
                            ttftMs = raw.ttftMs,
                            thermal = (envAfter ?: raw.environment).thermalState.name,
                            batteryDelta = ((envBefore?.batteryLevel ?: raw.environment.batteryLevel) -
                                (envAfter?.batteryLevel ?: raw.environment.batteryLevel)).coerceAtLeast(0),
                            memPercent = raw.memUsagePercent,
                            cpuPercent = raw.avgCpuUsage,
                            energyMj = raw.energyMilliJoules,
                            score = 0.0,
                            failed = raw.failed,
                            note = raw.errorMessage ?: ""
                        )
                        collected += row to raw

                        _ui.value = _ui.value.copy(
                            stepIndex = step,
                            progress = step.toFloat() / totalSteps,
                            results = collected.map { it.first },
                            progressText = if (raw.failed) {
                                "第 $step/$totalSteps 步 · 失败：${raw.errorMessage ?: "未知"}"
                            } else {
                                "第 $step/$totalSteps 步 · 完成 TG ${"%.1f".format(raw.tgTps)} t/s · PP ${"%.1f".format(raw.ppTps)} t/s"
                            }
                        )
                    }
                }

                val scored = score(collected)
                val best = scored.filter { !it.failed }.maxByOrNull { it.score }
                val applied = best?.let { applyBest(it) }
                val summaryText = buildString {
                    if (best == null) {
                        append("没有可用结果：所有候选都失败，请查看日志或换一个模型/后端")
                    } else {
                        append("最优：${best.backend} · 线程 ${best.threads ?: "自动"} · KV ${best.kv} · batch ${best.batch}\n")
                        append("TG ${"%.2f".format(best.tgTps)} t/s · PP ${"%.2f".format(best.ppTps)} t/s · TTFT ${best.ttftMs}ms\n")
                        val thermalZh = when (best.thermal) {
                            "COOL" -> "常温"
                            "WARM" -> "温热"
                            "HOT" -> "偏热"
                            "CRITICAL" -> "过热"
                            else -> best.thermal
                        }
                        append("温度 $thermalZh · 内存 ${if (best.memPercent in 0..100) "${best.memPercent}%" else "未读到"} · ")
                        append("CPU ${if (best.cpuPercent in 0..100) "${best.cpuPercent}%" else "未读到"} · 得分 ${"%.3f".format(best.score)}")
                        if (applied != null) append("\n$applied")
                    }
                }
                _ui.value = _ui.value.copy(
                    results = scored,
                    best = best,
                    summary = summaryText,
                    progress = 1f,
                    stepIndex = totalSteps,
                    progressText = "完成 · 用时 ${(System.currentTimeMillis() - startedAt) / 1000}s",
                    infoMessage = if (best == null) null else "已应用最优配置"
                )
                LogSystem.info(TAG, "auto-tune done: level=$level best=${best?.let { "${it.backend}/th=${it.threads}/${it.kv}/b=${it.batch}" }}")
            } catch (t: Throwable) {
                LogSystem.error(TAG, "run failed: ${t.message}", t)
                _ui.value = _ui.value.copy(errorMessage = "调优失败：${t.message}")
            } finally {
                timerJob?.cancel()
                system.setRunning(false)
                _ui.value = _ui.value.copy(running = false)
            }
        }
    }

    // ============== internals ==============

    private data class Candidate(
        val threads: Int?,
        val kv: QuantType,
        val batch: Int,
        val context: Int,
        val ppSize: Int,
        val tgSize: Int,
        val repCount: Int,
        val warmup: Int
    )

    /** 用于「计划步数」预览的配置：不读 DataStore，用保守默认值。 */
    private fun previewBaseConfig(): InferenceConfigV2 = InferenceConfigV2().copy(
        threads = null,
        batchSize = 512,
        contextLength = 4096,
        kvQuant = QuantType.Q8_0
    )

    /** 读取「设置 → 推理」当前生效配置，作为候选的基线。 */
    private suspend fun currentConfig(): InferenceConfigV2 {
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

    private fun buildCandidates(level: Level, base: InferenceConfigV2): List<Candidate> {
        val big = CpuInfo.bigCores.coerceIn(1, 16)
        val threads = when (level) {
            Level.INSTANT, Level.LOW -> listOf(null, big)
            Level.MEDIUM -> listOf(null, big, 4)
            Level.HIGH, Level.EXTREME -> listOf(null, big, 4, 6)
        }.distinct()
        val kv = when (level) {
            Level.INSTANT, Level.LOW -> listOf(base.kvQuant)
            Level.MEDIUM -> listOf(base.kvQuant, QuantType.Q8_0)
            Level.HIGH -> listOf(QuantType.F16, QuantType.Q8_0)
            Level.EXTREME -> listOf(QuantType.F16, QuantType.Q8_0, QuantType.Q4_0)
        }.distinct()
        val batch = when (level) {
            Level.HIGH, Level.EXTREME -> listOf(512, 1024)
            else -> listOf(base.batchSize.coerceAtLeast(1))
        }.distinct()

        val src = when (level) {
            Level.INSTANT -> Load(64, 16, 1, 0)
            Level.LOW -> Load(128, 32, 2, 1)
            Level.MEDIUM -> Load(256, 64, 2, 1)
            Level.HIGH -> Load(512, 128, 3, 1)
            Level.EXTREME -> Load(1024, 256, 5, if (_ui.value.hotTest) 3 else 1)
        }

        return buildList {
            for (t in threads) for (q in kv) for (b in batch) {
                add(Candidate(t, q, b, base.contextLength, src.pp, src.tg, src.reps, src.warmup))
            }
        }
    }

    private data class Load(val pp: Int, val tg: Int, val reps: Int, val warmup: Int)

    /**
     * 综合打分：
     * - 速度：TG 0.50 / PP 0.25 / TTFT 0.10（低延迟更好）
     * - 能效：0.15（读得到能耗时）
     * - 惩罚：热档（HOT/CRITICAL）、内存占用过高、KV 精度下降
     */
    private fun score(
        rows: List<Pair<CandidateResult, BenchmarkResult>>
    ): List<CandidateResult> {
        val ok = rows.filter { !it.first.failed }
        val maxTg = ok.maxOfOrNull { it.first.tgTps }?.takeIf { it > 0 } ?: 1.0
        val maxPp = ok.maxOfOrNull { it.first.ppTps }?.takeIf { it > 0 } ?: 1.0
        val minTtft = ok.filter { it.first.ttftMs > 0 }.minOfOrNull { it.first.ttftMs } ?: 1L
        val energies = ok.mapNotNull { it.second.energyEfficiencyTokensPerJoule }.filter { it > 0 }
        val maxE = energies.maxOrNull()

        return rows.map { (c, raw) ->
            if (c.failed) {
                c.copy(score = 0.0)
            } else {
                val nTg = (c.tgTps / maxTg).coerceIn(0.0, 1.0)
                val nPp = (c.ppTps / maxPp).coerceIn(0.0, 1.0)
                val nTtft = if (c.ttftMs > 0) (minTtft.toDouble() / c.ttftMs).coerceIn(0.0, 1.0) else 0.0
                val e = raw.energyEfficiencyTokensPerJoule
                val speedPart = if (e != null && maxE != null && maxE > 0) {
                    0.50 * nTg + 0.25 * nPp + 0.10 * nTtft + 0.15 * (e / maxE)
                } else {
                    0.62 * nTg + 0.30 * nPp + 0.08 * nTtft
                }
                val quality = when (c.kv) {
                    QuantType.Q4_0 -> 0.90
                    QuantType.Q8_0 -> 0.97
                    else -> 1.0
                }
                val thermalPenalty = when (c.thermal) {
                    ThermalState.CRITICAL.name -> 0.60
                    ThermalState.HOT.name -> 0.82
                    ThermalState.WARM.name -> 0.95
                    else -> 1.0
                }
                val memPenalty = when {
                    c.memPercent !in 0..100 -> 1.0
                    c.memPercent >= 92 -> 0.70
                    c.memPercent >= 85 -> 0.85
                    c.memPercent >= 78 -> 0.95
                    else -> 1.0
                }
                c.copy(score = speedPart * quality * thermalPenalty * memPenalty)
            }
        }
    }

    private suspend fun applyBest(best: CandidateResult): String {
        val cfg = container.settingsRepository.config.firstOrNull() ?: InferenceConfig()
        val kv = when (best.kv) {
            QuantType.F16 -> "f16"
            QuantType.Q8_0 -> "q8_0"
            QuantType.Q4_0 -> "q4_0"
            else -> cfg.kvQuant
        }
        container.settingsRepository.save(
            cfg.copy(
                cpuThreads = best.threads ?: 0,
                kvQuant = kv,
                physicalBatch = best.batch,
                contextLength = best.context
            )
        )
        return "已写回设置：线程 ${best.threads ?: "自动"} · KV $kv · batch ${best.batch} · 上下文 ${best.context}"
    }

    private companion object {
        const val TAG = "aibox.autotune"
    }
}
