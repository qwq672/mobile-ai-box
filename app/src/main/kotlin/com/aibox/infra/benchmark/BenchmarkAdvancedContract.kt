package com.aibox.infra.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

/**
 * 高级基准契约（从 feature/benchmark 契约移植到 app 模块）。
 *
 * 说明：原契约位于 feature/benchmark（未接入 Gradle 构建，依赖 Hilt/Truth/Turbine，
 * 与本项目手动 DI（AppContainer）不一致）。为避免引入独立模块的构建风险，
 * 这里把契约数据类与采样源直接落地到 app 模块，零外部依赖、可编译、可出包。
 *
 * 反屎山原则：
 * - 数据类与 UI 解耦：[BenchmarkSample] 只描述一次采样，UI 自行决定如何绘制。
 * - 双档模式：[BenchmarkMode.BASIC] 只出汇总；[BenchmarkMode.ADVANCED] 额外产出 6 条曲线。
 * - 不支持的硬件能力返回 null，UI 自动隐藏对应曲线（用户已允许）。
 */

/** 基准模式 */
enum class BenchmarkMode {
    /** 基础：一次测量，只看汇总数字，不做曲线。 */
    BASIC,

    /** 高级：边推理边采样，产出 6 条曲线 + 汇总报告。 */
    ADVANCED,
}

/** 采样阶段 */
enum class BenchmarkPhase { PREFILL, DECODE, IDLE }

/** 单次采样快照 */
data class BenchmarkSample(
    val elapsedMs: Long,
    val phase: BenchmarkPhase,
    val basic: SampleBasicMetrics,
    val advanced: SampleAdvancedMetrics? = null,
)

/** 基础指标（两档都用） */
data class SampleBasicMetrics(
    val outputTokensPerSecond: Double,
    val prefillTokensPerSecond: Double,
    val memoryUsageMb: Long,
)

/**
 * 高级指标 —— 对应契约中的 6 条曲线。
 *
 * 数据来源诚实标注：
 * - [memoryUsageRatioCurve]：系统内存占用率（used/total），来自 ActivityManager，真实测量。
 * - [outputSpeedCurve]：decode 阶段瞬时 token/s，按采样窗口统计，真实测量。
 * - [prefillSpeedCurve]：首 token 前按 ppSize/TTFT 估算的单点值，非逐 token 实测。
 * - [cpuUtilizationCurve]：本进程 CPU 占用（/proc/self/stat 差分），真实测量。
 * - [temperatureCurve]：CPU 温度（sysfs thermal_zone）；GPU/NPU 多数机型无公开接口，为 null。
 * - [npuUtilizationCurve]：NPU 占用率；除个别厂商外无公开 API，通常为 null（UI 隐藏）。
 */
data class SampleAdvancedMetrics(
    val memoryUsageRatioCurve: CurvePoint,
    val prefillSpeedCurve: CurvePoint,
    val outputSpeedCurve: CurvePoint,
    val temperatureCurve: TemperaturePoint?,
    val npuUtilizationCurve: NpuUtilizationPoint?,
    val cpuUtilizationCurve: CpuUtilizationPoint,
)

data class CurvePoint(val timestampMs: Long, val value: Double)

data class TemperaturePoint(
    val timestampMs: Long,
    val cpuTempCelsius: Float,
    val gpuTempCelsius: Float? = null,
    val npuTempCelsius: Float? = null,
)

data class NpuUtilizationPoint(
    val timestampMs: Long,
    val utilization: Float,      // 0..1
    val idleBaseline: Float,     // 空闲基线
)

data class CpuUtilizationPoint(
    val timestampMs: Long,
    val appCpuUsage: Float,      // 0..1，仅本进程
    val userTimeMs: Long,
    val kernelTimeMs: Long,
)

/** 汇总报告（测试结束后生成） */
data class BenchmarkReport(
    val backendName: String,
    val modelName: String,
    val mode: BenchmarkMode,
    val totalSamples: Int,
    val averages: SampleBasicMetrics,
    val outputSpeedStats: Stats?,
    val cpuStats: Stats?,
    val memoryStats: Stats?,
    val startedAt: Long,
    val finishedAt: Long,
)

/** 描述性统计：min/max/mean/stdDev 与分位数，用于评估波动。 */
data class Stats(
    val min: Double,
    val max: Double,
    val mean: Double,
    val stdDev: Double,
    val p50: Double,
    val p95: Double,
    val p99: Double,
) {
    companion object {
        /** 由一串采样值计算统计。空列表返回 null。 */
        fun of(values: List<Double>): Stats? {
            val valid = values.filter { it.isFinite() }
            if (valid.isEmpty()) return null
            val sorted = valid.sorted()
            val mean = valid.sum() / valid.size
            val variance = if (valid.size > 1) valid.sumOf { (it - mean) * (it - mean) } / (valid.size - 1) else 0.0
            fun percentile(p: Double): Double {
                if (sorted.size == 1) return sorted[0]
                val idx = (sorted.size - 1) * p
                val lo = kotlin.math.floor(idx).toInt()
                val hi = kotlin.math.ceil(idx).toInt()
                if (lo == hi) return sorted[lo]
                return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
            }
            return Stats(
                min = sorted.first(),
                max = sorted.last(),
                mean = mean,
                stdDev = kotlin.math.sqrt(variance),
                p50 = percentile(0.50),
                p95 = percentile(0.95),
                p99 = percentile(0.99),
            )
        }
    }
}

/**
 * 硬件采样源 —— 高级模式所需的温度、进程 CPU、内存都来自这里。
 *
 * 接口零依赖，便于单元测试 mock。不支持的能力返回 null，UI 自动隐藏对应曲线。
 */
interface SampleSources {
    suspend fun cpuTemperature(): Float?
    suspend fun gpuTemperature(): Float?
    suspend fun npuTemperature(): Float?
    suspend fun npuUtilization(idleBaseline: Float?): Float?
    suspend fun appCpuUsage(): Float
    suspend fun memoryUsageRatio(): Float
}

/**
 * Android 默认采样源：通过 sysfs / proc / Android API 读取硬件指标。
 *
 * 所有读取都 try-catch 兜底：读不到返回 null / 0f，绝不阻塞基准测试。
 * - CPU 温度：/sys/class/thermal/thermal_zoneN/temp（找 cpu/soc/max 类型）
 * - 进程 CPU：/proc/self/stat 的 utime+stime 差分（两次调用之间）
 * - 内存占用率：ActivityManager.getMemoryInfo(). availMem / totalMem
 * - GPU / NPU 温度与占用：无稳定公开 API，返回 null
 */
class AndroidSampleSources(private val context: Context) : SampleSources {

    @Volatile
    private var lastCpuJiffies: Long? = null

    @Volatile
    private var lastCpuTimeMs: Long = 0L

    override suspend fun cpuTemperature(): Float? = readCpuTemperature()

    override suspend fun gpuTemperature(): Float? = null // 无稳定公开接口

    override suspend fun npuTemperature(): Float? = null // 无稳定公开接口

    override suspend fun npuUtilization(idleBaseline: Float?): Float? = null // 无稳定公开接口

    override suspend fun appCpuUsage(): Float {
        return try {
            val line = File("/proc/self/stat").readText()
            // 格式: pid (comm) state ppid ... utime(14) stime(15) ...
            // comm 可能含空格/括号，取最后一个 ')' 之后切分
            val afterComm = line.substringAfterLast(')').trim().split(Regex("\\s+"))
            // afterComm[0]=state ... 用户态字段索引 = 14-3 = 11, 内核 = 12
            val utime = afterComm.getOrNull(11)?.toLongOrNull() ?: return 0f
            val stime = afterComm.getOrNull(12)?.toLongOrNull() ?: return 0f
            val jiffies = utime + stime
            val now = System.currentTimeMillis()
            val prevJ = lastCpuJiffies
            val prevT = lastCpuTimeMs
            lastCpuJiffies = jiffies
            lastCpuTimeMs = now
            if (prevJ == null || prevT <= 0L || now <= prevT) return 0f
            val deltaJ = (jiffies - prevJ).coerceAtLeast(0L)
            val deltaMs = (now - prevT).coerceAtLeast(1L)
            val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            // jiffies 通常 100Hz：jiffies/100 = CPU 秒；除以墙钟秒与核数 => 0..1
            val ratio = (deltaJ / 100.0) / (deltaMs / 1000.0) / cpuCount
            ratio.coerceIn(0.0, 1.0).toFloat()
        } catch (_: Throwable) {
            0f
        }
    }

    override suspend fun memoryUsageRatio(): Float {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0f
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            if (info.totalMem <= 0L) return 0f
            val used = info.totalMem - info.availMem
            (used.toFloat() / info.totalMem.toFloat()).coerceIn(0f, 1f)
        } catch (_: Throwable) {
            0f
        }
    }

    /** 读系统内存信息（MB），供基础指标 memoryUsageMb 使用。 */
    fun memoryUsageMb(): Long {
        return try {
            val rt = Runtime.getRuntime()
            val jvmUsed = rt.totalMemory() - rt.freeMemory()
            val native = Debug.getNativeHeapAllocatedSize()
            ((jvmUsed + native) / (1024 * 1024)).coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        }
    }

    private fun readCpuTemperature(): Float? {
        return try {
            val base = File("/sys/class/thermal")
            val zones = base.listFiles()?.filter { it.name.startsWith("thermal_zone") } ?: return null
            val ordered = zones.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            for (zone in ordered) {
                val type = runCatching { File(zone, "type").readText().trim().lowercase() }.getOrNull() ?: continue
                if (type.contains("cpu") || type.contains("soc") || type.contains("max") || type.contains("tsens")) {
                    val raw = runCatching { File(zone, "temp").readText().trim().toFloat() }.getOrNull() ?: continue
                    // sysfs 多数以毫摄氏度上报（如 42000 = 42℃）
                    return if (raw > 1000f) raw / 1000f else raw
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 采样源能力探测：UI 据此决定显示几条曲线。 */
    suspend fun detectCapabilities(): SampleSourceCapabilities = SampleSourceCapabilities(
        cpuTemp = cpuTemperature() != null,
        gpuTemp = gpuTemperature() != null,
        npuTemp = npuTemperature() != null,
        npuUtilization = npuUtilization(null) != null,
    )
}

data class SampleSourceCapabilities(
    val cpuTemp: Boolean,
    val gpuTemp: Boolean,
    val npuTemp: Boolean,
    val npuUtilization: Boolean,
)
