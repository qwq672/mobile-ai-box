package com.aibox.infra.benchmark

import com.aibox.domain.inference.InferenceBackendV2
import com.aibox.domain.inference.InferenceRequest
import com.aibox.domain.inference.LoadedModel
import com.aibox.domain.inference.ModalityInput
import com.aibox.domain.inference.ModalityOutput
import com.aibox.domain.inference.TaskType
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.collect
import java.util.UUID

/**
 * Layer 1 基础基准测试执行器。
 *
 * 按 [BenchmarkSpec] 跑 `warmup + repCount` 次推理，取中位数得到 [BenchmarkResult]。
 *
 * 每次推理：
 * 1. 生成约 [BenchmarkSpec.ppSize] 个 token 的 prompt（用占位文本拼装）。
 * 2. 调 [InferenceBackendV2.streamInfer]，逐 chunk 计时。
 *    - TTFT = 首个非空 chunk 的时间 - 开始时间
 *    - 总时间 = done chunk 的时间 - 开始时间
 *    - PP t/s = ppSize / (TTFT 秒)（近似：TTFT 主要是 prompt 处理 + KV 分配 + 首 decode）
 *    - TG t/s = 生成 token 数 / (总时间 - TTFT) 秒
 * 3. 跑完后读峰值内存、能耗。
 *
 * 中位数算法：对 [BenchmarkSpec.repCount] 次的每个指标分别排序，取中间元素（repCount 通常为 3，取 sorted[1]）。
 *
 * 日志：用 [LogSystem.startSession] 创建 sessionId，每次推理写一行原始数据到 benchmark 独立日志，
 * 最后写一份 summary（中位数结果）。
 *
 * 异常时返回 [BenchmarkResult.failed]，不抛出（零异常约定）。
 *
 * @param environmentMonitor 用于前后环境快照
 * @param energyProfiler 用于前后能耗采样
 * @param peakMemoryProvider 可选的自定义峰值内存读取；null 时用 [defaultPeakMemoryBytes]
 */
class BasicBenchmark(
    private val environmentMonitor: EnvironmentMonitor,
    private val energyProfiler: EnergyProfiler,
    private val peakMemoryProvider: (() -> Long)? = null
) {

    private val tag = "aibox.benchmark.basic"

    /**
     * 执行一次 [spec]。
     *
     * @param backend 已加载该模型的后端实例（调用方负责 [InferenceBackendV2.loadModel] 成功后再调本方法）。
     * @param loadedModel [backend.loadModel] 返回的 [LoadedModel] 句柄。
     * @param spec 测试规格。
     */
    suspend fun run(
        backend: InferenceBackendV2,
        loadedModel: LoadedModel,
        spec: BenchmarkSpec
    ): BenchmarkResult {
        val sessionId = LogSystem.startSession(
            "benchmark",
            "${backend.getName()}_${loadedModel.modelPath.substringAfterLast('/')}_${spec.ppSize}x${spec.tgSize}"
        )
        LogSystem.info(tag, "=== BasicBenchmark start: backend=${backend.getName()} model=${loadedModel.modelPath} ppSize=${spec.ppSize} tgSize=${spec.tgSize} repCount=${spec.repCount} warmup=${spec.warmup} sessionId=$sessionId")

        // 失败兜底：任何阶段抛异常都返回 failed result，不让上层崩
        return try {
            doRun(backend, loadedModel, spec, sessionId)
        } catch (t: Throwable) {
            LogSystem.error(tag, "BasicBenchmark failed: ${t.message}", t, sessionId = sessionId)
            BenchmarkResult.failed(spec, t.message ?: t.javaClass.simpleName)
        } finally {
            LogSystem.endSession(sessionId, "BasicBenchmark end: $spec")
        }
    }

    private suspend fun doRun(
        backend: InferenceBackendV2,
        loadedModel: LoadedModel,
        spec: BenchmarkSpec,
        sessionId: String
    ): BenchmarkResult {
        val envBefore = environmentMonitor.snapshot()
        LogSystem.info(tag, "EnvBefore: ${envBefore.deviceSoC} thermal=${envBefore.thermalState} bgCpu=${envBefore.backgroundCpuUsage}% battery=${envBefore.batteryLevel}%", sessionId = sessionId)

        val energyBefore = energyProfiler.before()
        val promptText = generatePrompt(spec.ppSize)

        // 推理配置:覆盖 maxTokens = tgSize,启用 streaming
        val runConfig = spec.config.copy(maxTokens = spec.tgSize)

        // ---- 预热(不计入统计) ----
        repeat(spec.warmup.coerceAtLeast(0)) { idx ->
            LogSystem.debug(tag, "warmup #${idx + 1}/${spec.warmup}", sessionId = sessionId)
            runOnce(backend, loadedModel, spec, promptText, runConfig, sessionId, isWarmup = true)
        }

        // ---- 正式重复 ----
        val reps = mutableListOf<RepResult>()
        // 测试期间采样 CPU/GPU/内存,记录最大值(峰值)与平均值
        val cpuSamples = mutableListOf<Int>()
        val gpuSamples = mutableListOf<Int>()
        val memSamples = mutableListOf<Int>()
        val appPssSamples = mutableListOf<Long>()
        val appNativeSamples = mutableListOf<Long>()

        repeat(spec.repCount.coerceAtLeast(1)) { idx ->
            val r = runOnce(backend, loadedModel, spec, promptText, runConfig, sessionId, isWarmup = false)
            // 每次推理后采样一次(若想高频,可在 runOnce 内部采样,但开销大)
            sampleDeviceUsage(cpuSamples, gpuSamples, memSamples, appPssSamples, appNativeSamples)
            LogSystem.info(
                tag,
                "rep #${idx + 1}/${spec.repCount}: ppTps=${"%.2f".format(r.ppTps)} tgTps=${"%.2f".format(r.tgTps)} ttft=${r.ttftMs}ms total=${r.totalTimeMs}ms peakMem=${r.peakMem}",
                sessionId = sessionId
            )
            reps.add(r)
        }

        // ---- 取中位数 ----
        // 过滤无效 rep (0 token EOG 立即返回的), 只要有至少 1 个有效 rep 就用有效的算
        val validReps = reps.filter { it.tgTps > 0 || it.ppTps > 0 }.ifEmpty { reps }
        val medianPpTps = medianDouble(validReps.map { it.ppTps })
        val medianTgTps = medianDouble(validReps.map { it.tgTps })
        val medianTtft = medianLong(validReps.map { it.ttftMs })
        val medianTotal = medianLong(validReps.map { it.totalTimeMs })
        val peakMem = reps.map { it.peakMem }.maxOrNull() ?: 0L

        // ---- 能耗 / 环境快照后 ----
        val energyAfter = energyProfiler.after()
        val energyMj = energyProfiler.computeMilliJoules(energyBefore, energyAfter)
        val envAfter = environmentMonitor.snapshot()
        LogSystem.info(tag, "EnvAfter: thermal=${envAfter.thermalState} bgCpu=${envAfter.backgroundCpuUsage}% battery=${envAfter.batteryLevel}% energyMj=$energyMj", sessionId = sessionId)

        // ---- 设备状态聚合 ----
        val avgCpu = if (cpuSamples.isNotEmpty()) cpuSamples.filter { it in 0..100 }.average().toInt() else -1
        val avgGpu = if (gpuSamples.isNotEmpty()) gpuSamples.filter { it in 0..100 }.average().toInt() else -1
        val avgMem = if (memSamples.isNotEmpty()) memSamples.filter { it in 0..100 }.average().toInt() else -1
        val peakPss = appPssSamples.filter { it > 0 }.maxOrNull() ?: -1L
        val peakNative = appNativeSamples.filter { it > 0 }.maxOrNull() ?: -1L

        // ---- 能耗比 ----
        val energyEff = BenchmarkResult.computeEnergyEfficiency(medianTgTps, energyMj, medianTotal)

        // 后置环境用 "before"(保持与 spec 一致;详细变化已写在日志)
        val envSnapshot = envBefore

        val summary = """
            === BasicBenchmark Summary ===
            Backend: ${backend.getName()}
            Model: ${loadedModel.modelPath}
            Spec: pp=${spec.ppSize} tg=${spec.tgSize} rep=${spec.repCount}
            PP t/s (median): ${"%.2f".format(medianPpTps)}
            TG t/s (median): ${"%.2f".format(medianTgTps)}
            TTFT (median): ${medianTtft}ms
            Total time (median): ${medianTotal}ms
            Peak memory: ${peakMem / 1024 / 1024}MB
            App PSS peak: ${peakPss}MB
            App Native peak: ${peakNative}MB
            System mem usage (avg): ${if (avgMem >= 0) "$avgMem%" else "未读到"}
            CPU usage (avg): ${if (avgCpu >= 0) "$avgCpu%" else "未读到"}
            GPU usage (avg): ${if (avgGpu >= 0) "$avgGpu%" else "未读到"}
            Energy: ${energyMj ?: "unknown"} mJ
            Energy efficiency: ${energyEff?.let { "%.2f tokens/J".format(it) } ?: "unknown"}
            Environment before: ${envBefore.deviceSoC} thermal=${envBefore.thermalState} bgCpu=${envBefore.backgroundCpuUsage}% battery=${envBefore.batteryLevel}% ramFree=${envBefore.freeRamMb}MB
            Environment after : thermal=${envAfter.thermalState} bgCpu=${envAfter.backgroundCpuUsage}% battery=${envAfter.batteryLevel}% ramFree=${envAfter.freeRamMb}MB
        """.trimIndent()

        LogSystem.writeBenchmarkResult(sessionId, summary)

        return BenchmarkResult(
            spec = spec,
            ppTps = medianPpTps,
            tgTps = medianTgTps,
            ttftMs = medianTtft,
            totalTimeMs = medianTotal,
            peakMemoryBytes = peakMem,
            energyMilliJoules = energyMj,
            memUsagePercent = avgMem,
            appPssMb = peakPss,
            appNativeMb = peakNative,
            energyEfficiencyTokensPerJoule = energyEff,
            avgCpuUsage = avgCpu,
            avgGpuUsage = avgGpu,
            environment = envSnapshot,
            logFilePath = null, // 由 BenchmarkStorage / 上层补 path
            timestamp = System.currentTimeMillis()
        )
    }

    /** 采样 CPU/GPU/内存利用率,追加到对应 list。读不到的项追加 -1,统计时过滤。 */
    private fun sampleDeviceUsage(
        cpu: MutableList<Int>,
        gpu: MutableList<Int>,
        mem: MutableList<Int>,
        appPss: MutableList<Long>,
        appNative: MutableList<Long>
    ) {
        // CPU 利用率:从 EnvironmentMonitor 读 backgroundCpuUsage 近似
        try {
            val env = environmentMonitor.snapshot()
            cpu.add(env.backgroundCpuUsage.coerceAtLeast(-1))
        } catch (_: Throwable) { cpu.add(-1) }

        // GPU 利用率:本类无法直接读(需要 ThermalMonitor 路径),先填 -1,后续可注入
        gpu.add(-1)

        // 系统内存使用率:用 EnvironmentMonitor 读
        try {
            val env = environmentMonitor.snapshot()
            val total = env.totalRamMb
            val free = env.freeRamMb
            if (total > 0) {
                val used = total - free
                val pct = (used * 100 / total).toInt().coerceIn(0, 100)
                mem.add(pct)
            } else {
                mem.add(-1)
            }
        } catch (_: Throwable) { mem.add(-1) }

        // app PSS / Native
        try {
            val dm = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(dm)
            val pss = dm.totalPss / 1024L
            val native = dm.nativePss / 1024L
            appPss.add(pss)
            appNative.add(native)
        } catch (_: Throwable) {
            appPss.add(-1L)
            appNative.add(-1L)
        }
    }

    /**
     * 跑一次推理，返回单次的 [RepResult]。
     *
     * @param isWarmup true 时只跑不记录详细日志（仍记录关键事件）
     */
    private suspend fun runOnce(
        backend: InferenceBackendV2,
        loadedModel: LoadedModel,
        spec: BenchmarkSpec,
        promptText: String,
        runConfig: com.aibox.domain.inference.InferenceConfigV2,
        sessionId: String,
        isWarmup: Boolean
    ): RepResult {
        val request = InferenceRequest(
            taskId = UUID.randomUUID().toString(),
            taskType = TaskType.TEXT_GENERATION,
            modelId = loadedModel.id,
            inputs = listOf(ModalityInput.Text(promptText)),
            config = runConfig,
            stream = true,
            sessionId = sessionId
        )

        val start = System.currentTimeMillis()
        var firstTokenTime = 0L
        var generatedTokens = 0

        try {
            val flow = backend.streamInfer(request, loadedModel)
            flow.collect { chunk ->
                val text = (chunk.partial as? ModalityOutput.Text)?.content.orEmpty()
                if (text.isNotEmpty()) {
                    if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                    // 简化：每个非空 chunk 视为 1 个 token（多数后端的实现）
                    generatedTokens++
                }
                if (chunk.done) {
                    // 流结束
                }
            }
        } catch (t: Throwable) {
            // 任意一次推理异常都视为本 rep 失败，记 0
            LogSystem.error(tag, "runOnce ${if (isWarmup) "warmup" else "rep"} threw: ${t.message}", t, sessionId = sessionId)
            return RepResult(0.0, 0.0, 0L, 0L, readPeakMemory())
        }

        val end = System.currentTimeMillis()
        val totalMs = (end - start).coerceAtLeast(1L)
        val ttft = (firstTokenTime - start).coerceAtLeast(0L).let { if (it == 0L) totalMs else it }

        // 保护: 生成 0 token (EOG at i=0) 时本次 rep 无效, 直接返回 0 避免指标爆表
        if (generatedTokens <= 0) {
            LogSystem.warn(tag, "runOnce generated 0 tokens (model EOG immediately), rep invalid", sessionId = sessionId)
            return RepResult(0.0, 0.0, 0L, totalMs, readPeakMemory())
        }

        // PP t/s = ppSize / TTFT 秒（近似）
        val ppTps = if (ttft > 0) spec.ppSize.toDouble() * 1000.0 / ttft else 0.0
        // TG t/s = generatedTokens / (total - TTFT) 秒
        val tgMs = (totalMs - ttft).coerceAtLeast(1L)
        val tgTps = if (generatedTokens > 0) generatedTokens.toDouble() * 1000.0 / tgMs else 0.0

        return RepResult(
            ppTps = ppTps,
            tgTps = tgTps,
            ttftMs = ttft,
            totalTimeMs = totalMs,
            peakMem = readPeakMemory()
        )
    }

    /**
     * 生成约 [approxTokens] 个 token 长度的 prompt 文本。
     *
     * 简化估算：英文约 4 字符 = 1 token；中文约 2-3 字符 = 1 token。这里用英文占位文本拼装，
     * 每段单元约 12 个 token。后端的实际 token 数由 tokenizer 决定，可能与本估算有 5-15% 偏差，
     * 但对"同 spec 跨后端对比"足够（误差均匀）。
     */
    private fun generatePrompt(approxTokens: Int): String {
        val unit = "The quick brown fox jumps over the lazy dog. "
        val targetChars = (approxTokens * 4).coerceAtLeast(20)
        val sb = StringBuilder(targetChars + unit.length + 32)
        while (sb.length < targetChars) sb.append(unit)
        // 关键: 末尾加续写引导句, 否则裸重复文本会让小模型立即输出 EOG
        // (实测 LFM2.5-350M 对裸重复文本 EOG at i=0, tgTps=0, ppTps 爆表 64000)
        sb.append("\nOnce upon a time, the fox")
        return sb.toString()
    }

    /** 读当前进程峰值内存：JVM 堆使用 + native 堆分配（如 SDK 支持）。 */
    private fun readPeakMemory(): Long {
        peakMemoryProvider?.let { return it() }
        return defaultPeakMemoryBytes()
    }

    private fun defaultPeakMemoryBytes(): Long {
        val rt = Runtime.getRuntime()
        val jvmUsed = rt.totalMemory() - rt.freeMemory()
        val nativeUsed = try {
            // Debug.getNativeHeapAllocatedSize() 返回 native 堆已分配字节
            android.os.Debug.getNativeHeapAllocatedSize()
        } catch (_: Throwable) { 0L }
        return (jvmUsed + nativeUsed).coerceAtLeast(0L)
    }

    private fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun medianLong(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2L
    }

    private data class RepResult(
        val ppTps: Double,
        val tgTps: Double,
        val ttftMs: Long,
        val totalTimeMs: Long,
        val peakMem: Long
    )
}
