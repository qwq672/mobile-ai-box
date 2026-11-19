package com.aibox.infra.benchmark

import com.aibox.domain.inference.InferenceBackendV2
import com.aibox.domain.inference.InferenceRequest
import com.aibox.domain.inference.LoadedModel
import com.aibox.domain.inference.ModalityInput
import com.aibox.domain.inference.ModalityOutput
import com.aibox.domain.inference.TaskType
import java.util.UUID

/**
 * 流式基准执行器 —— 补齐契约层缺失的"边推理边采样 + 6 曲线 + 汇总报告"。
 *
 * 与 [BasicBenchmark] 的区别：
 * - BasicBenchmark：跑 warmup+rep 次，每次推理结束后采样一次，只产出中位数汇总（[BenchmarkResult]）。
 * - StreamingBenchmark：单次推理过程中按采样间隔持续采样，产出 [BenchmarkSample] 流供 UI 画曲线，
 *   结束后产出 [BenchmarkReport]（含 p50/p95/p99 波动统计）。
 *
 * 两者互补：基础档仍走 BasicBenchmark（结果可存历史 / 参与自动调优），高级档走本类。
 *
 * 零异常约定：任何失败都通过返回值 [BenchmarkReport.failed] 或"无样本"体现，不向上抛。
 */
class StreamingBenchmark(
    private val sources: AndroidSampleSources,
) {

    /**
     * 执行一次流式测量。
     *
     * @param onSample 每个采样点回调一次，UI 可实时追加到曲线（在主线程收集即可）。
     * @return 汇总报告；失败时返回一个带 error 标记、总样本为 0 的报告。
     */
    suspend fun run(
        backend: InferenceBackendV2,
        loadedModel: LoadedModel,
        spec: BenchmarkSpec,
        mode: BenchmarkMode,
        sampleIntervalMs: Long = 500L,
        onSample: (BenchmarkSample) -> Unit = {},
    ): BenchmarkReport {
        val startedAt = System.currentTimeMillis()
        val runConfig = spec.config.copy(maxTokens = spec.tgSize)
        val request = InferenceRequest(
            taskId = UUID.randomUUID().toString(),
            taskType = TaskType.TEXT_GENERATION,
            modelId = loadedModel.id,
            inputs = listOf(ModalityInput.Text(generatePrompt(spec.ppSize))),
            config = runConfig,
            stream = true,
        )

        val samples = mutableListOf<BenchmarkSample>()
        var firstTokenAt = 0L
        var tokenCount = 0
        var windowTokenStart = 0
        var lastSampleAt = startedAt
        var prefillTps = 0.0

        try {
            val flow = backend.streamInfer(request, loadedModel)
            flow.collect { chunk ->
                val now = System.currentTimeMillis()
                val text = (chunk.partial as? ModalityOutput.Text)?.content.orEmpty()
                if (text.isNotEmpty()) {
                    if (firstTokenAt == 0L) {
                        firstTokenAt = now
                        val ttft = (now - startedAt).coerceAtLeast(1L)
                        prefillTps = spec.ppSize.toDouble() * 1000.0 / ttft
                    }
                    tokenCount++
                }

                // 到达采样间隔，或收到 done —— 采一次
                val due = now - lastSampleAt >= sampleIntervalMs
                if (due || chunk.done) {
                    val elapsed = (now - startedAt).coerceAtLeast(0L)
                    val phase = if (firstTokenAt == 0L) BenchmarkPhase.PREFILL
                                else if (chunk.done) BenchmarkPhase.IDLE
                                else BenchmarkPhase.DECODE

                    val windowSec = ((now - lastSampleAt).coerceAtLeast(1L)) / 1000.0
                    val outTps = (tokenCount - windowTokenStart).toDouble() / windowSec
                    windowTokenStart = tokenCount
                    lastSampleAt = now

                    val advanced = if (mode == BenchmarkMode.ADVANCED) {
                        val cpuRatio = sources.appCpuUsage()
                        val temp = sources.cpuTemperature()
                        SampleAdvancedMetrics(
                            memoryUsageRatioCurve = CurvePoint(elapsed, sources.memoryUsageRatio().toDouble()),
                            // 预填充速度是单点估算：首 token 后第一个样本带上，其余为 0（UI 绘制时跳过 0）
                            prefillSpeedCurve = CurvePoint(elapsed, if (firstTokenAt != 0L && prefillTps > 0 && samples.isEmpty()) prefillTps else 0.0),
                            outputSpeedCurve = CurvePoint(elapsed, outTps.coerceAtLeast(0.0)),
                            temperatureCurve = temp?.let { TemperaturePoint(elapsed, it) },
                            npuUtilizationCurve = sources.npuUtilization(null)?.let { NpuUtilizationPoint(elapsed, it, 0f) },
                            cpuUtilizationCurve = CpuUtilizationPoint(
                                timestampMs = elapsed,
                                appCpuUsage = cpuRatio,
                                userTimeMs = 0L,
                                kernelTimeMs = 0L,
                            ),
                        )
                    } else null

                    val sample = BenchmarkSample(
                        elapsedMs = elapsed,
                        phase = phase,
                        basic = SampleBasicMetrics(
                            outputTokensPerSecond = outTps.coerceAtLeast(0.0),
                            prefillTokensPerSecond = if (phase == BenchmarkPhase.PREFILL) prefillTps else 0.0,
                            memoryUsageMb = sources.memoryUsageMb(),
                        ),
                        advanced = advanced,
                    )
                    samples += sample
                    onSample(sample)
                }
            }
        } catch (t: Throwable) {
            // 零异常：失败时保留已采到的样本，报告里标注失败
            return buildReport(
                backend, loadedModel, spec, mode, samples, startedAt,
                error = t.message ?: t.javaClass.simpleName,
            )
        }

        return buildReport(backend, loadedModel, spec, mode, samples, startedAt, error = null)
    }

    private fun buildReport(
        backend: InferenceBackendV2,
        loadedModel: LoadedModel,
        spec: BenchmarkSpec,
        mode: BenchmarkMode,
        samples: List<BenchmarkSample>,
        startedAt: Long,
        error: String?,
    ): BenchmarkReport {
        val outValues = samples.map { it.advanced?.outputSpeedCurve?.value ?: it.basic.outputTokensPerSecond }
        val cpuValues = samples.mapNotNull { it.advanced?.cpuUtilizationCurve?.appCpuUsage?.toDouble() }
        val memValues = samples.mapNotNull { it.advanced?.memoryUsageRatioCurve?.value }

        val prefill = samples.firstOrNull { it.basic.prefillTokensPerSecond > 0 }?.basic?.prefillTokensPerSecond ?: 0.0
        val avgOut = if (outValues.isNotEmpty()) outValues.sum() / outValues.size else 0.0

        // 用 error 前缀标记失败报告（UI 显示但不当作有效结果）
        return BenchmarkReport(
            backendName = if (error == null) backend.getName() else "${backend.getName()} · 失败:$error",
            modelName = loadedModel.modelPath.substringAfterLast('/'),
            mode = mode,
            totalSamples = samples.size,
            averages = SampleBasicMetrics(
                outputTokensPerSecond = avgOut,
                prefillTokensPerSecond = prefill,
                memoryUsageMb = samples.lastOrNull()?.basic?.memoryUsageMb ?: 0L,
            ),
            outputSpeedStats = Stats.of(outValues),
            cpuStats = Stats.of(cpuValues),
            memoryStats = Stats.of(memValues),
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
        )
    }

    /** 生成约 [approxTokens] 个 token 的 prompt（与 BasicBenchmark 同策略，末尾加续写引导避免立即 EOG）。 */
    private fun generatePrompt(approxTokens: Int): String {
        val unit = "The quick brown fox jumps over the lazy dog. "
        val targetChars = (approxTokens * 4).coerceAtLeast(20)
        val sb = StringBuilder(targetChars + unit.length + 32)
        while (sb.length < targetChars) sb.append(unit)
        sb.append("\nOnce upon a time, the fox")
        return sb.toString()
    }
}
