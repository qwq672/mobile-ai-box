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
import com.aibox.util.CpuInfo
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
 * 自动调优 ViewModel。
 *
 * 与基准测试的分工：
 * - 基准测试：只测「当前配置」，输出的数字用于观察，不改任何设置。
 * - 自动调优：以基准测试为执行引擎，批量对比候选配置（后端 / 线程 / KV / batch），
 *   打分选出最优一组并写回「设置 → 推理」。
 *
 * 档位只决定候选空间和负载强度；[Level.INSTANT] 也会做最小对比，而不是纯规则拍脑袋。
 */
class AutoTuneViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val system get() = container.benchmarkSystem

    /** 调优档位：候选空间与负载强度递增。 */
    enum class Level(val label: String, val desc: String) {
        INSTANT("即时", "只比较线程「自动 / 大核数」两档，轻负载单次，几秒完成"),
        LOW("低", "线程两档 + 当前 KV，轻负载，重复 2 次"),
        MEDIUM("中", "线程三档 × KV 两档，中等负载"),
        HIGH("高", "线程四档 × KV 两档 × batch 两档；多后端时先选出最优后端"),
        EXTREME("极致", "线程四档 × KV 三档 × batch 两档；多后端先对比，长负载 + 更多重复")
    }

    /** 单个候选配置的实测结果。 */
    data class CandidateResult(
        val backend: String,
        val threads: Int?,
        val kv: QuantType,
        val batch: Int,
        val context: Int,
        val ppTps: Double,
        val tgTps: Double,
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
        val results: List<CandidateResult> = emptyList(),
        val best: CandidateResult? = null,
        val summary: String? = null,
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
                selectedBackendNames = if (_ui.value.selectedBackendNames.isEmpty()) {
                    list.map { it.getName() }.toSet()
                } else {
                    _ui.value.selectedBackendNames
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

        val level = _ui.value.level
        _ui.value = _ui.value.copy(
            running = true,
            progress = 0f,
            progressText = "准备中…",
            results = emptyList(),
            best = null,
            summary = null,
            errorMessage = null,
            infoMessage = null
        )
        system.setRunning(true)

        viewModelScope.launch {
            try {
                val base = currentConfig()
                val (backends, backendNote) = pickBackend(modelPath, base)
                val candidates = buildCandidates(level, base)
                val totalSteps = candidates.size * backends.size
                var done = 0
                val collected = mutableListOf<Pair<CandidateResult, BenchmarkResult>>()

                // 「设备发烫时测试」：先跑一段持续负载把设备带入高温状态，再开始测量。
                if (_ui.value.hotTest && level.ordinal >= Level.HIGH.ordinal) {
                    _ui.value = _ui.value.copy(progressText = "预热：持续负载加热设备…")
                    backends.firstOrNull()?.let { name ->
                        container.backendManager.getBackend(name)?.let { backend ->
                            val heatSpec = BenchmarkSpec(
                                modelPath, base.copy(maxTokens = 256),
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

                for (backendName in backends) {
                    val backend = container.backendManager.getBackend(backendName) ?: continue
                    for (candidate in candidates) {
                        _ui.value = _ui.value.copy(
                            progressText = "$backendName · 线程 ${candidate.threads ?: "自动"} · " +
                                "KV ${candidate.kv} · batch ${candidate.batch}",
                            progress = if (totalSteps > 0) done.toFloat() / totalSteps else 0f
                        )
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
                        val load = withContext(Dispatchers.IO) { backend.loadModel(modelPath, spec.config) }
                        val loaded = load.getOrNull()
                        val raw = if (loaded == null) {
                            BenchmarkResult.failed(spec, load.reasonOrNull() ?: "loadModel failed")
                        } else {
                            try {
                                withContext(Dispatchers.IO) {
                                    system.basicBenchmark.run(backend, loaded, spec)
                                }
                            } catch (t: Throwable) {
                                BenchmarkResult.failed(spec, t.message ?: t::class.java.simpleName)
                            } finally {
                                withContext(Dispatchers.IO) { runCatching { backend.unload(loaded) } }
                            }
                        }
                        collected += CandidateResult(
                            backend = backendName,
                            threads = candidate.threads,
                            kv = candidate.kv,
                            batch = candidate.batch,
                            context = candidate.context,
                            ppTps = raw.ppTps,
                            tgTps = raw.tgTps,
                            score = 0.0,
                            failed = raw.failed,
                            note = raw.errorMessage ?: ""
                        ) to raw
                        done++
                        _ui.value = _ui.value.copy(
                            progress = if (totalSteps > 0) done.toFloat() / totalSteps else 0f
                        )
                    }
                }

                val scored = score(collected)
                val best = scored.filter { !it.failed }.maxByOrNull { it.score }
                val applied = best?.let { applyBest(it) }
                val summaryText = buildString {
                    if (backendNote.isNotBlank()) appendLine(backendNote)
                    if (best == null) {
                        append("没有可用结果：所有候选都失败，请查看日志")
                    } else {
                        append("最优：${best.backend} · 线程 ${best.threads ?: "自动"} · KV ${best.kv} · " +
                            "batch ${best.batch} · TG ${"%.2f".format(best.tgTps)} t/s · " +
                            "PP ${"%.2f".format(best.ppTps)} t/s")
                        if (applied != null) appendLine().append(applied)
                    }
                }
                _ui.value = _ui.value.copy(
                    results = scored,
                    best = best,
                    summary = summaryText,
                    progress = 1f,
                    progressText = "完成",
                    infoMessage = if (best == null) null else "已应用最优配置"
                )
                LogSystem.info(TAG, "auto-tune done: level=$level best=${best?.let { "${it.backend}/th=${it.threads}/${it.kv}/b=${it.batch}" }}")
            } catch (t: Throwable) {
                LogSystem.error(TAG, "run failed: ${t.message}", t)
                _ui.value = _ui.value.copy(errorMessage = "调优失败：${t.message}")
            } finally {
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

    /**
     * 高 / 极致档先比较后端；其余档直接在所有选中后端上跑候选。
     * 返回「实际要调优的后端列表」与一段可展示说明（说明为空表示没做后端比较）。
     */
    private suspend fun pickBackend(
        modelPath: String,
        base: InferenceConfigV2
    ): Pair<List<String>, String> {
        val selected = _ui.value.selectedBackendNames.toList()
        if (_ui.value.level.ordinal < Level.HIGH.ordinal || selected.size <= 1) {
            return selected to ""
        }
        _ui.value = _ui.value.copy(progressText = "先比较 ${selected.size} 个后端…")
        val report = withContext(Dispatchers.IO) {
            system.backendComparison.compareBackends(
                modelPath = modelPath,
                modelFormat = modelPath.substringAfterLast('.', "").lowercase(),
                backends = selected,
                baseConfig = base
            )
        }
        val rec = report.recommendedBackend
        val target = if (rec.isNotBlank() && rec in selected) listOf(rec) else selected
        return target to "后端对比：${report.recommendation}"
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
            Level.HIGH -> listOf(512, 1024)
            Level.EXTREME -> listOf(512, 1024)
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
     * 打分：速度为主，同时抑制盲目堆线程 / 低精度 KV。
     * - TG 归一化权重 0.60，PP 归一化权重 0.30；
     * - 有能耗比时占 0.10，否则该权重不计（保持各候选同基线）；
     * - KV 精度惩罚：Q8_0 ×0.97，Q4_0 ×0.90。
     */
    private fun score(
        rows: List<Pair<CandidateResult, BenchmarkResult>>
    ): List<CandidateResult> {
        val ok = rows.filter { !it.first.failed }
        val maxTg = ok.maxOfOrNull { it.first.tgTps }?.takeIf { it > 0 } ?: 1.0
        val maxPp = ok.maxOfOrNull { it.first.ppTps }?.takeIf { it > 0 } ?: 1.0
        val energies = ok.mapNotNull { it.second.energyEfficiencyTokensPerJoule }.filter { it > 0 }
        val maxE = energies.maxOrNull()
        return rows.map { (c, raw) ->
            if (c.failed) c.copy(score = 0.0) else {
                val nTg = c.tgTps / maxTg
                val nPp = c.ppTps / maxPp
                val e = raw.energyEfficiencyTokensPerJoule
                val quality = when (c.kv) {
                    QuantType.Q4_0 -> 0.90
                    QuantType.Q8_0 -> 0.97
                    else -> 1.0
                }
                val base = if (e != null && maxE != null && maxE > 0) {
                    0.60 * nTg + 0.30 * nPp + 0.10 * (e / maxE)
                } else {
                    0.65 * nTg + 0.35 * nPp
                }
                c.copy(score = base * quality)
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

    private companion object {
        const val TAG = "aibox.autotune"
    }
}
