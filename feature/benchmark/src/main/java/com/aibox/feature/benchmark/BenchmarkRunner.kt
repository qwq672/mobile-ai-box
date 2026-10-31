package com.aibox.feature.benchmark

import kotlinx.coroutines.flow.Flow

/**
 * 基准测试契约 — 接口零依赖，便于插件测试也复用。
 *
 * 反屎山原则：
 * - 接口与 UI 解耦：[BenchmarkRunner] 只产出 [BenchmarkSample] 流，UI 自己渲染
 * - 双档模式：[BenchmarkMode.BASIC]（用户主用，3 项指标）和 [BenchmarkMode.ADVANCED]（专业用户，6 曲线）
 * - 实时流式：[samples] Flow 让 UI 实时绘制曲线，不是跑完一次性返回
 *
 * 设计参考：docs/NUDGE_AND_DIAGNOSTICS.md "基准测试设计"
 */
public interface BenchmarkRunner {

    /**
     * 启动基准测试。
     *
     * @param config 测试配置（目标后端、模型、模式、持续时间）
     * @return 实时采样流，UI 直接 collect
     */
    public fun run(config: BenchmarkConfig): Flow<BenchmarkSample>

    /** 取消当前测试 */
    public fun cancel()

    /** 是否正在运行 */
    public val isRunning: Flow<Boolean>
}

/**
 * 基准测试配置。
 *
 * @param mode 基础 / 高级
 * @param backendId 目标推理后端 ID
 * @param modelPath 模型路径
 * @param durationSeconds 总测试时长（基础默认 30s，高级默认 120s）
 * @param sampleIntervalMs 采样间隔（基础 1000ms，高级 500ms）
 */
public data class BenchmarkConfig(
    public val mode: BenchmarkMode,
    public val backendId: String,
    public val modelPath: String,
    public val prompt: String = "请解释什么是机器学习，并给出三个生活中的例子。",
    public val maxTokens: Int = 512,
    public val durationSeconds: Int = if (mode == BenchmarkMode.BASIC) 30 else 120,
    public val sampleIntervalMs: Long = if (mode == BenchmarkMode.BASIC) 1000 else 500,
)

public enum class BenchmarkMode {
    /**
     * 基础模式：
     * - 仅展示 3 个平均指标（输出速度、预填充速度、内存占用）
     * - 不绘制曲线图
     * - 时长约 30 秒
     * - 适合普通用户快速对比后端
     */
    BASIC,

    /**
     * 高级模式：
     * - 包含基础所有指标
     * - 加 6 条曲线图：内存占用率、预填充速度波动、输出速度波动、温度波动、NPU 占用率、CPU 占用率
     * - 时长约 120 秒，采样更密集
     * - 适合发烧友调优和性能分析
     */
    ADVANCED,
}

/**
 * 单次采样快照。
 *
 * [BasicMetrics] 在两种模式下都填充；
 * [AdvancedMetrics] 仅 [BenchmarkMode.ADVANCED] 填充，基础模式为 null。
 */
public data class BenchmarkSample(
    public val elapsedMs: Long,
    public val phase: BenchmarkPhase,
    public val basic: BasicMetrics,
    public val advanced: AdvancedMetrics? = null,
)

public enum class BenchmarkPhase {
    /** 预填充阶段（处理 prompt） */
    PREFILL,

    /** 输出阶段（生成 tokens） */
    DECODE,

    /** 等待下一次推理 */
    IDLE,
}

/**
 * 基础指标（两档模式都用）。
 */
public data class BasicMetrics(
    public val outputTokensPerSecond: Double,
    public val prefillTokensPerSecond: Double,
    public val memoryUsageMb: Long,
)

/**
 * 高级指标（仅 ADVANCED 模式）。
 *
 * 字段对应你列出的 6 条曲线：
 * - [memoryUsageRatioCurve]   : 内存占用率曲线
 * - [prefillSpeedCurve]       : 预填充速度波动
 * - [outputSpeedCurve]        : 输出速度波动
 * - [temperatureCurve]        : 温度波动（CPU/GPU/NPU）
 * - [npuUtilizationCurve]     : NPU 占用率（含空闲时基线）
 * - [cpuUtilizationCurve]     : CPU 占用率（仅软件内）
 *
 * 注意：[npuUtilizationCurve] 包含空闲时 NPU 占用率采样：
 * - 测试开始前先采样 5 秒空闲 NPU 占用率作为基线
 * - 推理过程中实时采样对比
 * - 部分厂商（如华为 HiAI）API 不公开占用率 → 该字段为 null（用户已允许）
 */
public data class AdvancedMetrics(
    public val memoryUsageRatioCurve: CurvePoint,
    public val prefillSpeedCurve: CurvePoint,
    public val outputSpeedCurve: CurvePoint,
    public val temperatureCurve: TemperaturePoint?,
    public val npuUtilizationCurve: NpuUtilizationPoint?,
    public val cpuUtilizationCurve: CpuUtilizationPoint,
)

public data class CurvePoint(
    public val timestampMs: Long,
    public val value: Double,
)

public data class TemperaturePoint(
    public val timestampMs: Long,
    public val cpuTempCelsius: Float,
    public val gpuTempCelsius: Float?,
    public val npuTempCelsius: Float?,
)

public data class NpuUtilizationPoint(
    public val timestampMs: Long,
    public val utilization: Float,        // 0..1
    public val idleBaseline: Float,      // 空闲时基线（启动前测得）
)

public data class CpuUtilizationPoint(
    public val timestampMs: Long,
    /** 仅统计 AIBox 进程的 CPU 占用率（0..1），非系统级 */
    public val appCpuUsage: Float,
    /** 用户态 + 内核态时间，单位 ms */
    public val userTimeMs: Long,
    public val kernelTimeMs: Long,
)

/**
 * 基准测试最终汇总报告。
 *
 * 测试结束后由 [BenchmarkRunner] 通过 Flow 的最后一个 [BenchmarkSample.report] 派发。
 */
public data class BenchmarkReport(
    public val config: BenchmarkConfig,
    public val totalSamples: Int,
    public val averages: BasicMetrics,
    public val advancedSummary: AdvancedSummary?,
    public val startedAt: Long,
    public val finishedAt: Long,
)

public data class AdvancedSummary(
    public val outputSpeedStats: Stats,
    public val prefillSpeedStats: Stats,
    public val memoryStats: Stats,
    public val cpuStats: Stats,
    public val npuStats: Stats?,
    public val temperatureStats: TemperatureStats?,
)

public data class Stats(
    public val min: Double,
    public val max: Double,
    public val mean: Double,
    public val stdDev: Double,
    public val p50: Double,
    public val p95: Double,
    public val p99: Double,
)

public data class TemperatureStats(
    public val cpuMax: Float,
    public val gpuMax: Float?,
    public val npuMax: Float?,
)
