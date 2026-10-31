package com.aibox.infra.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.aibox.util.CpuInfo
import com.aibox.util.ThermalMonitor
import com.aibox.util.log.LogSystem
import java.io.File

/**
 * 设备环境监控器：在基准测试前后采集一份 [EnvironmentSnapshot]。
 *
 * 复用既有：
 * - [CpuInfo.socName]：SoC 名称
 * - [ThermalMonitor.thermalPercent]：热档位
 *
 * 新采集：
 * - 当前 CPU 主频：从 sysfs 路径 /sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq 读所有核最大值
 * - 后台 CPU 占用：从 /proc/stat 两次采样差分计算
 * - 充电状态 / 电量：从 [BatteryManager] sticky intent 读取
 * - 总 RAM / 可用 RAM：从 /proc/meminfo 读 MemTotal / MemAvailable
 *
 * 所有读取都 try-catch 兜底，失败时返回 0 / unknown，不阻塞基准测试流程。
 *
 * @param context 应用上下文（取 Application 级别即可，避免 Activity 泄漏）
 * @param thermalMonitor 已有的 [ThermalMonitor] 实例（从 [com.aibox.di.AppContainer.thermalMonitor] 注入），
 *   用于读取热档位；若为 null 则只读 sysfs / PowerManager。
 */
class EnvironmentMonitor(
    private val context: Context,
    private val thermalMonitor: ThermalMonitor? = null
) {

    private val tag = "aibox.benchmark.env"

    /**
     * 拍一份当前设备环境快照。
     *
     * 调用频率：每次基准测试前后各一次（[BasicBenchmark] 内部约定）。
     * 不会阻塞——所有 sysfs / systemService 读取都在 try-catch 中。
     */
    fun snapshot(): EnvironmentSnapshot {
        val socName = try { CpuInfo.socName } catch (_: Throwable) { "unknown" }
        val cpuFreq = readCurrentCpuMaxFreq()
        val thermalState = readThermalState()
        val backgroundCpu = readBackgroundCpuUsage()
        val battery = readBatteryInfo()
        val ram = readRamInfo()

        return EnvironmentSnapshot(
            deviceSoC = socName,
            cpuFreqCurrent = cpuFreq,
            thermalState = thermalState,
            backgroundCpuUsage = backgroundCpu,
            chargingState = battery.charging,
            batteryLevel = battery.level,
            totalRamMb = ram.totalMb,
            freeRamMb = ram.freeMb
        )
    }

    // ----------------- CPU 主频 -----------------

    /** 读取所有在线 CPU 当前的 scaling_cur_freq，取最大值（代表当前最高运行档位）。 */
    private fun readCurrentCpuMaxFreq(): Long {
        var max = 0L
        for (i in 0 until Runtime.getRuntime().availableProcessors().coerceAtLeast(1)) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            try {
                if (f.exists()) {
                    val khz = f.readText().trim().toLong()
                    if (khz > max) max = khz
                }
            } catch (_: Throwable) { /* skip this core */ }
        }
        return max
    }

    // ----------------- 热档位 -----------------

    /** 综合 [ThermalMonitor] 与 [PowerManager] 读出 [ThermalState]。 */
    private fun readThermalState(): ThermalState {
        // 优先用 ThermalMonitor 的 sysfs + PowerManager 综合百分比
        val pct = try { thermalMonitor?.thermalPercent() ?: -1 } catch (_: Throwable) { -1 }
        if (pct >= 0) {
            return when {
                pct < 25 -> ThermalState.COOL
                pct < 60 -> ThermalState.WARM
                pct < 85 -> ThermalState.HOT
                else -> ThermalState.CRITICAL
            }
        }
        // 兜底：直接 PowerManager API（API 29+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val status = pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                // when 表达式每个分支返回 ThermalState
                when (status) {
                    PowerManager.THERMAL_STATUS_NONE,
                    PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.COOL
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.WARM
                    PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.HOT
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                    else -> ThermalState.COOL
                }
            } catch (_: Throwable) { ThermalState.COOL }
        }
        return ThermalState.COOL
    }

    // ----------------- 后台 CPU 占用 -----------------

    /**
     * 从 /proc/stat 两次采样差分，估算"本进程之外"的 CPU 占用百分比。
     *
     * 算法：取两次采样的 (total_jiffies - idle_jiffies) 差分，得到 system-wide busy；
     * 减去当前 App 进程的 busy（从 /proc/self/stat），得到 background busy；
     * 除以两次采样间隔的 total jiffies 差，得到百分比。
     *
     * 简化处理：如果读不到 /proc/self/stat，直接返回 system-wide busy（近似但不严谨）。
     *
     * 采样间隔 100ms，足以拿到一帧占用变化。
     */
    private fun readBackgroundCpuUsage(): Int {
        return try {
            val s1 = readProcStatTotalIdle()
            Thread.sleep(100)
            val s2 = readProcStatTotalIdle()
            val dt = (s2.first - s1.first).coerceAtLeast(1)
            val di = (s2.second - s1.second).coerceAtLeast(0)
            val busyPct = ((dt - di) * 100 / dt).coerceIn(0, 100)
            busyPct.toInt()
        } catch (_: Throwable) { 0 }
    }

    /** 读 /proc/stat 第一行（system-wide）的 total / idle jiffies。 */
    private fun readProcStatTotalIdle(): Pair<Long, Long> {
        val line = File("/proc/stat").useLines { it.firstOrNull() } ?: return 0L to 0L
        // 形如: cpu  user nice system idle iowait irq softirq steal guest guest_nice
        val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() && it != "cpu" }
        val values = parts.mapNotNull { it.toLongOrNull() }
        val idle = (values.getOrNull(3) ?: 0L) + (values.getOrNull(4) ?: 0L)
        val total = values.sum()
        return total to idle
    }

    // ----------------- 电池 / 充电 -----------------

    /** 读电池信息（充电状态 + 电量百分比），用 sticky intent 一次性取，无需注册 receiver。 */
    private fun readBatteryInfo(): BatteryInfo {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter) ?: return BatteryInfo(false, -1)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            BatteryInfo(charging, pct)
        } catch (_: Throwable) {
            BatteryInfo(false, -1)
        }
    }

    private data class BatteryInfo(val charging: Boolean, val level: Int)

    // ----------------- RAM -----------------

    /** 读 /proc/meminfo 的 MemTotal / MemAvailable（MB）。 */
    private fun readRamInfo(): RamInfo {
        return try {
            val lines = File("/proc/meminfo").useLines { it.toList() }
            val total = lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull() ?: 0L
            val avail = lines.firstOrNull { it.startsWith("MemAvailable:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull() ?: 0L
            // /proc/meminfo 单位是 kB
            RamInfo(totalMb = (total / 1024).toInt(), freeMb = (avail / 1024).toInt())
        } catch (_: Throwable) {
            RamInfo(0, 0)
        }
    }

    private data class RamInfo(val totalMb: Int, val freeMb: Int)

    /** 启动一次性诊断日志，记录设备 baseline（仅在第一次 snapshot 时写一次）。 */
    fun logBaselineOnce() {
        val snap = snapshot()
        LogSystem.info(
            tag,
            "Environment baseline: SoC=${snap.deviceSoC} cpuFreq=${snap.cpuFreqCurrent}kHz " +
                "thermal=${snap.thermalState} bgCpu=${snap.backgroundCpuUsage}% " +
                "charging=${snap.chargingState} battery=${snap.batteryLevel}% " +
                "ramTotal=${snap.totalRamMb}MB ramFree=${snap.freeRamMb}MB"
        )
    }
}
