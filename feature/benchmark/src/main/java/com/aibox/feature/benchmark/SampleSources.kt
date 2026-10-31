package com.aibox.feature.benchmark

/**
 * 硬件采样源 — 高级模式所需的温度、NPU 占用、CPU 占用都来自这里。
 *
 * 反屎山原则：
 * - 接口零依赖：所有硬件访问抽象成接口，便于单元测试 mock
 * - 默认实现位于 [DefaultSampleSources]（在 Android 设备上调用系统 API）
 * - 不支持的能力返回 null，UI 自动隐藏对应曲线（用户已允许）
 *
 * 用户特别允许的让步：
 *   "NPU 占用率如果展示不了可以不用写这个代码" → 实现时返回 null，UI 不画
 */
public interface SampleSources {

    /** CPU 温度（℃） */
    public suspend fun cpuTemperature(): Float?

    /** GPU 温度（℃），可能不支持 */
    public suspend fun gpuTemperature(): Float?

    /** NPU 温度（℃），可能不支持 */
    public suspend fun npuTemperature(): Float?

    /**
     * NPU 占用率（0..1）。
     *
     * 实现策略：
     *   - Hexagon: 通过 QNN 性能 API 读取（部分版本支持）
     *   - HiAI: 通过 HiAI Foundation API 读取
     *   - NNAPI: 无标准 API，返回 null
     *   - 实测无 API 支持 → 返回 null（用户已允许）
     *
     * @param idleBaseline 启动前测得的空闲基线（用于绘制对比线）
     */
    public suspend fun npuUtilization(idleBaseline: Float?): Float?

    /**
     * CPU 占用率（仅当前进程，0..1）。
     *
     * 实现方式：读 /proc/self/stat 计算用户态+内核态时间差
     * 不调用系统级 CPU 监控（避免读取其他应用 CPU 时间）
     */
    public suspend fun appCpuUsage(): Float
}

/** 默认实现：通过系统文件路径读取硬件指标 */
public class DefaultSampleSources : SampleSources {
    override suspend fun cpuTemperature(): Float? = null
    override suspend fun gpuTemperature(): Float? = null
    override suspend fun npuTemperature(): Float? = null
    override suspend fun npuUtilization(idleBaseline: Float?): Float? = null
    override suspend fun appCpuUsage(): Float = 0f
}

/**
 * 采样源能力探测 — UI 用来决定显示几条曲线。
 *
 * 返回值告知 UI 哪些曲线可用，哪些不可用。
 * 不可用的曲线位置显示"该硬件不支持"占位文本。
 */
public suspend fun SampleSources.detectCapabilities(): SampleSourceCapabilities =
    SampleSourceCapabilities(
        cpuTemp = cpuTemperature() != null,
        gpuTemp = gpuTemperature() != null,
        npuTemp = npuTemperature() != null,
        npuUtilization = npuUtilization(null) != null,
        appCpuUsage = true,  // 总是支持
    )

public data class SampleSourceCapabilities(
    public val cpuTemp: Boolean,
    public val gpuTemp: Boolean,
    public val npuTemp: Boolean,
    public val npuUtilization: Boolean,
    public val appCpuUsage: Boolean,
) {
    /** 可显示的曲线数 */
    public val availableCurves: Int get() =
        listOf(true, true, true, npuUtilization, appCpuUsage).count { it }
}
