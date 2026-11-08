package com.aibox.util

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.app.ActivityManager
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
 * 设备状态监控(原名 ThermalMonitor,功能扩展为综合设备状态)。
 *
 * 数据来源(按优先级,取先拿到的):
 * 1. /sys/class/thermal/thermal_zoneN/temp(sysfs,分辨率高,需扫描所有 zone 找 CPU/SKIN)
 * 2. PowerManager.currentThermalStatus(API 29+,离散等级 0-6)
 *
 * 扩展数据:
 * - 温度: percent / celsius / state(thermal level)
 * - CPU: 主频 + 利用率(/proc/stat 计算)
 * - GPU: 主频(/sys/class/kgsl/3d/... 或 mali/devfreq)
 * - 内存: 系统内存使用率 (ActivityManager.MemoryInfo)
 * - 进程内存: 当前 app 的 PSS / Java / Native 内存 (Debug.MemoryInfo)
 * - 电量: battery level + status + temperature
 *
 * 如果某项拿不到,返回 -1 或 0,UI 显示 "—" 而不是误导性的 "0%"。
 */
class ThermalMonitor {

    /** -1 = 未读到,0..100 = 热档位 */
    private val _thermalPercent = MutableStateFlow(-1)
    val thermalPercent: StateFlow<Int> = _thermalPercent.asStateFlow()

    /** 原始摄氏度;-1.0 表示未读到。来自 sysfs 或 PowerManager 估算。 */
    private val _celsius = MutableStateFlow(-1.0)
    val celsius: StateFlow<Double> = _celsius.asStateFlow()

    /** CPU 当前主频(kHz);0 表示读不到。取 cpu0~cpuN 中第一个能读到的。 */
    private val _cpuFreqKhz = MutableStateFlow(0L)
    val cpuFreqKhz: StateFlow<Long> = _cpuFreqKhz.asStateFlow()

    /** CPU 利用率(0..100);-1 表示读不到。基于 /proc/stat 差分计算。 */
    private val _cpuUsage = MutableStateFlow(-1)
    val cpuUsage: StateFlow<Int> = _cpuUsage.asStateFlow()

    /** GPU 当前主频(KHz);0 表示读不到(高通 Adreno 走 kgsl, Mali 走 devfreq)。 */
    private val _gpuFreqKhz = MutableStateFlow(0L)
    val gpuFreqKhz: StateFlow<Long> = _gpuFreqKhz.asStateFlow()

    /** GPU 利用率(0..100);-1 表示读不到。 */
    private val _gpuUsage = MutableStateFlow(-1)
    val gpuUsage: StateFlow<Int> = _gpuUsage.asStateFlow()

    /** 系统内存使用率(0..100);-1 表示读不到。 */
    private val _memUsage = MutableStateFlow(-1)
    val memUsage: StateFlow<Int> = _memUsage.asStateFlow()

    /** 当前 app 进程的 PSS 内存(MB);-1 表示读不到。 */
    private val _appPssMb = MutableStateFlow(-1L)
    val appPssMb: StateFlow<Long> = _appPssMb.asStateFlow()

    /** 当前 app 进程的 Native 内存(MB);-1 表示读不到。 */
    private val _appNativeMb = MutableStateFlow(-1L)
    val appNativeMb: StateFlow<Long> = _appNativeMb.asStateFlow()

    /** 电量百分比(0..100);-1 表示读不到。 */
    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    /** 电池温度(摄氏度);-1.0 表示读不到。 */
    private val _batteryCelsius = MutableStateFlow(-1.0)
    val batteryCelsius: StateFlow<Double> = _batteryCelsius.asStateFlow()

    /** 电池状态:Charging / Discharging / Full / Unknown。 */
    private val _batteryStatus = MutableStateFlow("Unknown")
    val batteryStatus: StateFlow<String> = _batteryStatus.asStateFlow()

    /** 历史采样(最近 60 秒)。每次 [start] 循环追加 1 个样本,超出 30 个时丢弃最老的。 */
    private val _history = MutableStateFlow<List<ThermalSample>>(emptyList())
    val history: StateFlow<List<ThermalSample>> = _history.asStateFlow()

    /** 散热档位枚举,UI 直接渲染。 */
    private val _state = MutableStateFlow(ThermalLevel.UNKNOWN)
    val state: StateFlow<ThermalLevel> = _state.asStateFlow()

    private var job: Job? = null
    private var sysfsPaths: List<String> = emptyList()
    private var cpuFreqPath: String? = null
    private var gpuFreqPaths: List<String> = emptyList()
    private var gpuUsagePaths: List<String> = emptyList()
    @Volatile private var pm: PowerManager? = null
    @Volatile private var bm: BatteryManager? = null
    @Volatile private var am: ActivityManager? = null

    /** 上次 /proc/stat 读取值,用于 CPU 利用率差分计算。 */
    private var prevCpuTotal: Long = -1
    private var prevCpuIdle: Long = -1

    fun start() {
        if (job?.isActive == true) return
        sysfsPaths = findAllTempZones()
        cpuFreqPath = findCpuFreqPath()
        gpuFreqPaths = findAllGpuFreqPaths()
        gpuUsagePaths = findAllGpuUsagePaths()
        pm = (appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        bm = (appContext?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
        am = (appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        LogSystem.info("aibox.thermal",
            "ThermalMonitor start: sysfs_zones=${sysfsPaths.size} cpuFreq=${cpuFreqPath != null} gpuFreq=${gpuFreqPaths.size} gpuUsage=${gpuUsagePaths.size} pm=${if (pm != null) "yes" else "no"} bm=${if (bm != null) "yes" else "no"} am=${if (am != null) "yes" else "no"}")
        job = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                tick()
                delay(2000)  // 2 秒一次,温度更实时
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun thermalPercent(): Int = _thermalPercent.value

    /**
     * 一次性快照:把当前所有状态字段打包成 [ThermalSnapshot],便于 UI 渲染整张卡片。
     */
    fun snapshot(): ThermalSnapshot = ThermalSnapshot(
        percent = _thermalPercent.value,
        celsius = _celsius.value,
        cpuFreqKhz = _cpuFreqKhz.value,
        cpuUsage = _cpuUsage.value,
        gpuFreqKhz = _gpuFreqKhz.value,
        gpuUsage = _gpuUsage.value,
        memUsage = _memUsage.value,
        appPssMb = _appPssMb.value,
        appNativeMb = _appNativeMb.value,
        batteryLevel = _batteryLevel.value,
        batteryCelsius = _batteryCelsius.value,
        batteryStatus = _batteryStatus.value,
        state = _state.value,
        history = _history.value,
        timestamp = System.currentTimeMillis()
    )

    /** 单次循环:读温度 + CPU/GPU 频率 + 利用率 + 内存 + 电量 + 追加 history + 推导 ThermalLevel。 */
    private fun tick() {
        // 1. 读温度 + 原始摄氏度
        val (pct, c) = readPercentWithCelsius()
        _thermalPercent.value = pct
        _celsius.value = c

        // 2. 读 CPU 主频
        _cpuFreqKhz.value = readCpuFreq()

        // 3. 读 CPU 利用率(差分)
        _cpuUsage.value = readCpuUsage()

        // 4. 读 GPU 主频 + 利用率
        _gpuFreqKhz.value = readGpuFreq()
        _gpuUsage.value = readGpuUsage()

        // 5. 读内存使用率 + app 内存
        readMemoryUsage()

        // 6. 读电量
        readBattery()

        // 7. 追加 history
        val sample = ThermalSample(
            timestamp = System.currentTimeMillis(),
            percent = pct,
            celsius = c,
            cpuFreqKhz = _cpuFreqKhz.value,
            cpuUsage = _cpuUsage.value,
            gpuFreqKhz = _gpuFreqKhz.value,
            gpuUsage = _gpuUsage.value,
            memUsage = _memUsage.value
        )
        // 仅在真正读到数据(pct >= 0 或 cpuFreq > 0 或 memUsage >= 0)时才追加,避免历史里全是 -1
        if (pct >= 0 || _cpuFreqKhz.value > 0 || _memUsage.value >= 0) {
            val newHist = (_history.value + sample).takeLast(HISTORY_MAX)
            _history.value = newHist
        }

        // 8. 推导 ThermalLevel
        _state.value = ThermalLevel.fromPercent(pct)
    }

    /**
     * 读温度百分比 + 原始摄氏度。返回 (percent, celsius)。
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
                val pct = (status * 100 / 6).coerceIn(0, 100)
                val estC = 35.0 + pct * 30.0 / 100.0
                pct to estC
            } catch (_: Exception) { -1 to -1.0 }
        }

        return -1 to -1.0
    }

    /** 读 CPU 当前主频(kHz);0 表示读不到。 */
    private fun readCpuFreq(): Long {
        val path = cpuFreqPath ?: return 0L
        return try {
            val raw = File(path).readText().trim()
            raw.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
    }

    /**
     * 读 CPU 利用率(0..100),基于 /proc/stat 差分计算。
     *
     * /proc/stat 第一行是所有 CPU 总和: "cpu  user nice system idle iowait irq softirq steal ..."
     * 利用率 = (delta_total - delta_idle) / delta_total * 100
     */
    private fun readCpuUsage(): Int {
        return try {
            val lines = File("/proc/stat").readText().split("\n")
            val firstLine = lines.firstOrNull { it.startsWith("cpu ") } ?: return -1
            val fields = firstLine.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (fields.size < 5) return -1
            // [cpu, user, nice, system, idle, iowait, irq, softirq, steal, ...]
            val idle = fields[4].toLong()
            // idle 包含 iowait;total = 所有时间累加
            var total = 0L
            for (i in 1 until fields.size) total += fields[i].toLong()
            val prevTotal = prevCpuTotal
            val prevIdle = prevCpuIdle
            prevCpuTotal = total
            prevCpuIdle = idle
            if (prevTotal < 0 || prevIdle < 0) return -1  // 第一次读无法算
            val dTotal = total - prevTotal
            val dIdle = idle - prevIdle
            if (dTotal <= 0) return -1
            (((dTotal - dIdle) * 100 / dTotal).toInt()).coerceIn(0, 100)
        } catch (_: Exception) { -1 }
    }

    /** 读 GPU 当前主频(KHz);0 表示读不到。尝试高通 kgsl / mali devfreq 两套路径。 */
    private fun readGpuFreq(): Long {
        for (path in gpuFreqPaths) {
            try {
                val raw = File(path).readText().trim()
                val freq = raw.toLongOrNull() ?: continue
                if (freq > 0) return freq
            } catch (_: Exception) { /* next */ }
        }
        return 0L
    }

    /** 读 GPU 利用率(0..100);-1 表示读不到。 */
    private fun readGpuUsage(): Int {
        for (path in gpuUsagePaths) {
            try {
                val raw = File(path).readText().trim()
                val v = raw.toIntOrNull() ?: continue
                // 不同 OEM 单位不同:有的是 0..100 直接是百分比,有的是 0..1000000 (ppm)
                val pct = when {
                    v <= 100   -> v          // 已是百分比
                    v <= 10000 -> v / 100    // 0..10000 → 0..100
                    else       -> (v / 10000).coerceAtMost(100) // 0..1000000 → 0..100
                }
                if (pct in 0..100) return pct
            } catch (_: Exception) { /* next */ }
        }
        return -1
    }

    /** 读系统内存使用率 + 当前 app 的 PSS / Native 内存。 */
    private fun readMemoryUsage() {
        val am = this.am ?: return
        try {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val total = mi.totalMem
            val avail = mi.availMem
            if (total > 0) {
                val used = total - avail
                _memUsage.value = ((used * 100 / total).toInt()).coerceIn(0, 100)
            }
        } catch (_: Exception) { _memUsage.value = -1 }

        // app 进程内存
        try {
            val dm = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(dm)
            _appPssMb.value = dm.totalPss / 1024L
            _appNativeMb.value = dm.nativePss / 1024L
        } catch (_: Exception) {
            _appPssMb.value = -1L
            _appNativeMb.value = -1L
        }
    }

    /** 读电量百分比 + 温度 + 状态。 */
    private fun readBattery() {
        val bm = this.bm ?: return
        try {
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            _batteryLevel.value = if (level in 0..100) level else -1

            // 温度 + 状态从 battery intent 拿 (BatteryManager 没有直接 API)
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val intent = appContext?.registerReceiver(null, filter) ?: return
            val temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temp > 0) _batteryCelsius.value = temp / 10.0
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            _batteryStatus.value = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NotCharging"
                android.os.BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
                else -> "Unknown"
            }
        } catch (_: Exception) {
            _batteryLevel.value = -1
            _batteryCelsius.value = -1.0
            _batteryStatus.value = "Unknown"
        }
    }

    /**
     * 扫描所有 thermal_zone,按温度从高到低排序。
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
     * 找 CPU 当前频率路径:cpu0 优先,依次尝试 cpu1..cpu7。
     */
    private fun findCpuFreqPath(): String? {
        for (i in 0 until 8) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            if (f.exists() && f.canRead()) return f.absolutePath
        }
        return null
    }

    /**
     * 扫描所有可能的 GPU 主频路径(高通 Adreno kgsl + Mali devfreq)。
     *
     * 高通 Adreno: /sys/class/kgsl/3d/gpuclk + /sys/class/kgsl/3d/devfreq/cur_freq
     * Mali:        /sys/class/devfreq/mali-0/cur_freq + /sys/class/misc/mali0/device/clock
     * PowerVR:     /sys/devices/platform/[name].img-rogue/cur_freq
     */
    private fun findAllGpuFreqPaths(): List<String> {
        val candidates = listOf(
            "/sys/class/kgsl/3d/gpuclk",
            "/sys/class/kgsl/3d/devfreq/cur_freq",
            "/sys/class/devfreq/mali-0/cur_freq",
            "/sys/class/misc/mali0/device/clock",
            "/sys/class/devfreq/soc:qcom,gpubw/cur_freq"
        )
        val result = mutableListOf<String>()
        for (c in candidates) {
            if (File(c).exists() && File(c).canRead()) {
                result.add(c)
            }
        }
        // 兜底:扫 /sys/class/devfreq/ 下所有 devfreq 设备
        val devfreqBase = File("/sys/class/devfreq")
        if (devfreqBase.exists()) {
            devfreqBase.listFiles { f -> f.isDirectory }?.forEach { d ->
                val cf = File(d, "cur_freq")
                if (cf.exists() && cf.canRead()) {
                    val abs = cf.absolutePath
                    // 排除 mali-0(已加)和 gpuclk 类
                    if (abs !in result && !abs.contains("mali-0")) {
                        result.add(abs)
                    }
                }
            }
        }
        return result
    }

    /**
     * 扫描所有可能的 GPU 利用率路径。
     *
     * 高通 Adreno: /sys/class/kgsl/3d/gpu_busy_percentage
     * Mali:        /sys/class/misc/mali0/device/utilisation
     */
    private fun findAllGpuUsagePaths(): List<String> {
        val candidates = listOf(
            "/sys/class/kgsl/3d/gpu_busy_percentage",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization"
        )
        val result = mutableListOf<String>()
        for (c in candidates) {
            if (File(c).exists() && File(c).canRead()) {
                result.add(c)
            }
        }
        return result
    }

    companion object {
        @Volatile var appContext: Context? = null

        /** 历史采样最大条数:60 秒 / 2 秒间隔 = 30 条。 */
        const val HISTORY_MAX = 30
    }
}

/**
 * 单次温度采样。
 *
 * @property timestamp 毫秒时间戳
 * @property percent 0..100 热档位;-1 表示未读到
 * @property celsius 原始摄氏度;-1.0 表示未读到
 * @property cpuFreqKhz CPU 当前主频 kHz;0 表示读不到
 * @property cpuUsage CPU 利用率 0..100;-1 表示读不到
 * @property gpuFreqKhz GPU 当前主频 kHz;0 表示读不到
 * @property gpuUsage GPU 利用率 0..100;-1 表示读不到
 * @property memUsage 系统内存使用率 0..100;-1 表示读不到
 */
data class ThermalSample(
    val timestamp: Long,
    val percent: Int,
    val celsius: Double,
    val cpuFreqKhz: Long,
    val cpuUsage: Int = -1,
    val gpuFreqKhz: Long = 0L,
    val gpuUsage: Int = -1,
    val memUsage: Int = -1
)

/**
 * 散热档位枚举(用于 UI 渲染色调 + 建议)。
 *
 * 阈值(与 ThermalGovernor 对齐):
 * - UNKNOWN: percent < 0(未读到温度)
 * - COOL: percent < 50(正常)
 * - WARM: percent 50..75(轻微发热,建议关注)
 * - HOT: percent 75..90(明显发热,建议降线程)
 * - CRITICAL: percent >= 90(极热,建议暂停长测)
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
 * 一次性快照:把 ThermalMonitor 当前所有 UI 需要的字段打包。
 *
 * 包含温度 / CPU / GPU / 内存 / 电量全套数据,UI 直接渲染整张设备状态卡片。
 */
data class ThermalSnapshot(
    val percent: Int,
    val celsius: Double,
    val cpuFreqKhz: Long,
    val cpuUsage: Int = -1,
    val gpuFreqKhz: Long = 0L,
    val gpuUsage: Int = -1,
    val memUsage: Int = -1,
    val appPssMb: Long = -1L,
    val appNativeMb: Long = -1L,
    val batteryLevel: Int = -1,
    val batteryCelsius: Double = -1.0,
    val batteryStatus: String = "Unknown",
    val state: ThermalLevel,
    val history: List<ThermalSample>,
    val timestamp: Long
)
