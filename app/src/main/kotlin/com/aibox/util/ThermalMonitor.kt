package com.aibox.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 热感知监控。
 *
 * 数据来源（按优先级，取先拿到的）：
 * 1. /sys/class/thermal/thermal_zoneN/temp（sysfs，分辨率高，需扫描所有 zone 找 CPU/SKIN）
 * 2. PowerManager.currentThermalStatus（API 29+，离散等级 0-6）
 *
 * 如果都拿不到，返回 -1，UI 显示 "热度 —" 而不是误导性的 "0%"。
 *
 * A2 扩展（向后兼容）：在原有 thermalPercent 之上新增
 * - [celsius]：原始摄氏度（sysfs 读到的值；PowerManager 来源时为估算值，-1 表示未读到）
 * - [cpuFreqKhz]：CPU 当前主频（kHz，0 表示读不到）
 * - [history]：最近 60 秒采样（默认 30 个样本，每 2 秒 1 个）
 * - [state]：[ThermalLevel] 枚举（UNKNOWN/COOL/WARM/HOT/CRITICAL），UI 直接用
 * - [snapshot]：[ThermalSnapshot] 一次性快照，便于 UI 直接渲染整张卡片
 *
 * 这些字段在 [start] 内部更新，与 [thermalPercent] 同步；外部读 StateFlow 即可。
 */
class ThermalMonitor {

    /** -1 = 未读到，0..100 = 热档位 */
    private val _thermalPercent = MutableStateFlow(-1)
    val thermalPercent: StateFlow<Int> = _thermalPercent.asStateFlow()

    /** 原始摄氏度；-1.0 表示未读到。来自 sysfs 或 PowerManager 估算。 */
    private val _celsius = MutableStateFlow(-1.0)
    val celsius: StateFlow<Double> = _celsius.asStateFlow()

    /** CPU 当前主频（kHz）；0 表示读不到。取 cpu0~cpuN 中第一个能读到的。 */
    private val _cpuFreqKhz = MutableStateFlow(0L)
    val cpuFreqKhz: StateFlow<Long> = _cpuFreqKhz.asStateFlow()

    /** 历史采样（最近 60 秒）。每次 [start] 循环追加 1 个样本，超出 30 个时丢弃最老的。 */
    private val _history = MutableStateFlow<List<ThermalSample>>(emptyList())
    val history: StateFlow<List<ThermalSample>> = _history.asStateFlow()

    /** 散热档位枚举，UI 直接渲染。 */
    private val _state = MutableStateFlow(ThermalLevel.UNKNOWN)
    val state: StateFlow<ThermalLevel> = _state.asStateFlow()

    private var job: Job? = null
    private var sysfsPaths: List<String> = emptyList()
    private var cpuFreqPath: String? = null
    @Volatile private var pm: PowerManager? = null

    fun start() {
        if (job?.isActive == true) return
        sysfsPaths = findAllTempZones()
        cpuFreqPath = findCpuFreqPath()
        pm = (appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        LogSystem.info("aibox.thermal",
            "ThermalMonitor start: sysfs_zones=${sysfsPaths.size} cpuFreq=${cpuFreqPath != null} pm=${if (pm != null) "yes" else "no"}")
        job = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                tick()
                delay(2000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun thermalPercent(): Int = _thermalPercent.value

    /**
     * 一次性快照：把当前所有状态字段打包成 [ThermalSnapshot]，便于 UI 渲染整张卡片。
     */
    fun snapshot(): ThermalSnapshot = ThermalSnapshot(
        percent = _thermalPercent.value,
        celsius = _celsius.value,
        cpuFreqKhz = _cpuFreqKhz.value,
        state = _state.value,
        history = _history.value,
        timestamp = System.currentTimeMillis()
    )

    /** 单次循环：读温度 + CPU 频率 + 追加 history + 推导 ThermalLevel。 */
    private fun tick() {
        // 1. 读温度 + 原始摄氏度
        val (pct, c) = readPercentWithCelsius()
        _thermalPercent.value = pct
        _celsius.value = c

        // 2. 读 CPU 主频
        _cpuFreqKhz.value = readCpuFreq()

        // 3. 追加 history
        val sample = ThermalSample(
            timestamp = System.currentTimeMillis(),
            percent = pct,
            celsius = c,
            cpuFreqKhz = _cpuFreqKhz.value
        )
        // 仅在真正读到数据（pct >= 0 或 cpuFreq > 0）时才追加，避免历史里全是 -1
        if (pct >= 0 || _cpuFreqKhz.value > 0) {
            val newHist = (_history.value + sample).takeLast(HISTORY_MAX)
            _history.value = newHist
        }

        // 4. 推导 ThermalLevel
        _state.value = ThermalLevel.fromPercent(pct)
    }

    /**
     * 读温度百分比 + 原始摄氏度。返回 (percent, celsius)。
     * - sysfs 路径读到 → 用 raw milli / 1000 = celsius，percent = (celsius - 35) * 100 / 30
     * - PowerManager 读到 → percent = status * 100 / 6，celsius = 35 + percent * 30 / 100（估算）
     * - 都失败 → (-1, -1.0)
     */
    private fun readPercentWithCelsius(): Pair<Int, Double> {
        for (path in sysfsPaths) {
            try {
                val raw = File(path).readText().trim()
                if (raw.isEmpty() || raw == "0") continue
                val milli = raw.toLong()
                if (milli <= 0) continue
                val celsiusVal = milli / 1000.0
                val pct = ((celsiusVal - 35) * 100 / 30).toInt().coerceIn(0, 100)
                if (pct >= 0) return pct to celsiusVal
            } catch (_: Exception) { /* try next */ }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = this.pm ?: return -1 to -1.0
            return try {
                val status = pm.currentThermalStatus
                // THERMAL_STATUS_NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6
                val pct = (status * 100 / 6).coerceIn(0, 100)
                // 估算 celsius：35 + percent * 30 / 100
                val estC = 35.0 + pct * 30.0 / 100.0
                pct to estC
            } catch (_: Exception) { -1 to -1.0 }
        }

        return -1 to -1.0
    }

    /** 旧 API 保留：只读 percent，与既有调用方兼容。 */
    private fun readPercent(): Int = readPercentWithCelsius().first

    /** 读 CPU 当前主频（kHz）；0 表示读不到。 */
    private fun readCpuFreq(): Long {
        val path = cpuFreqPath ?: return 0L
        return try {
            val raw = File(path).readText().trim()
            // scaling_cur_freq 单位是 kHz（与 cpuinfo_max_freq 一致）
            raw.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
    }

    /**
     * 扫描所有 thermal_zone，按温度从高到低排序。
     * 优先用 CPU zone（推理主要热源），其次 SKIN（手机表面温度）。
     */
    private fun findAllTempZones(): List<String> {
        val base = File("/sys/class/thermal")
        if (!base.exists()) return emptyList()
        val zones = base.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?: return emptyList()
        return zones.mapNotNull { zone ->
            try {
                val tempFile = File(zone, "temp")
                val typeFile = File(zone, "type")
                if (!tempFile.exists()) return@mapNotNull null
                val temp = tempFile.readText().trim().toLongOrNull() ?: return@mapNotNull null
                if (temp <= 0) return@mapNotNull null
                val type = if (typeFile.exists()) typeFile.readText().trim().lowercase() else ""
                Triple(tempFile.absolutePath, type, temp)
            } catch (_: Exception) { null }
        }.sortedByDescending { it.third }.map { it.first }
    }

    /**
     * 找 CPU 当前频率路径：cpu0 优先，依次尝试 cpu1..cpu7。
     * 部分手机只暴露部分核的 scaling_cur_freq；找到第一个能读的即可。
     */
    private fun findCpuFreqPath(): String? {
        for (i in 0 until 8) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            if (f.exists() && f.canRead()) return f.absolutePath
        }
        return null
    }

    companion object {
        @Volatile var appContext: Context? = null

        /** 历史采样最大条数：60 秒 / 2 秒间隔 = 30 条。 */
        const val HISTORY_MAX = 30
    }
}

/**
 * 单次温度采样。
 *
 * @property timestamp 毫秒时间戳
 * @property percent 0..100 热档位；-1 表示未读到
 * @property celsius 原始摄氏度；-1.0 表示未读到
 * @property cpuFreqKhz CPU 当前主频 kHz；0 表示读不到
 */
data class ThermalSample(
    val timestamp: Long,
    val percent: Int,
    val celsius: Double,
    val cpuFreqKhz: Long
)

/**
 * 散热档位枚举（用于 UI 渲染色调 + 建议）。
 *
 * 与 [com.aibox.infra.benchmark.ThermalState] 概念一致但阈值不同：
 * - benchmark 的 ThermalState 是基准测试环境快照的离散值；
 * - 本枚举是实时监控的 UI 档位（直接由 [ThermalMonitor.thermalPercent] 阈值推导）。
 *
 * 阈值（与 ThermalGovernor 对齐）：
 * - UNKNOWN: percent < 0（未读到温度）
 * - COOL: percent < 50（正常）
 * - WARM: percent 50..75（轻微发热，建议关注）
 * - HOT: percent 75..90（明显发热，建议降线程）
 * - CRITICAL: percent >= 90（极热，建议暂停长测）
 */
enum class ThermalLevel {
    UNKNOWN,
    COOL,
    WARM,
    HOT,
    CRITICAL;

    companion object {
        fun fromPercent(pct: Int): ThermalLevel = when {
            pct < 0 -> UNKNOWN
            pct < 50 -> COOL
            pct < 75 -> WARM
            pct < 90 -> HOT
            else -> CRITICAL
        }
    }
}

/**
 * 一次性快照：把 ThermalMonitor 当前所有 UI 需要的字段打包。
 *
 * 与逐个 StateFlow 监听相比，快照便于一次性渲染卡片；
 * UI 仍可单独订阅某个 StateFlow 做局部更新（如历史曲线随 history 变化）。
 */
data class ThermalSnapshot(
    val percent: Int,
    val celsius: Double,
    val cpuFreqKhz: Long,
    val state: ThermalLevel,
    val history: List<ThermalSample>,
    val timestamp: Long
)
