package com.aibox.infra.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.aibox.util.log.LogSystem

/**
 * 能耗测量器：在基准测试前后各采样一次，反推这次测试消耗的能量（毫焦耳）。
 *
 * 数据来源（按优先级）：
 * 1. **PowerManager 能耗计数器**（API 29+，[PowerManager EnergyDelegator]）：
 *    多数 Android 设备未开放，仅 Pixel 部分机型有；本类尝试调用，捕获 NoSuchMethodError。
 * 2. **BatteryManager 电量差反推**：
 *    用 [BatteryManager.BATTERY_PROPERTY_CURRENT_NOW] 与 [BATTERY_PROPERTY_CHARGE_COUNTER]
 *    估算能耗，单位毫焦耳 = mV * mA * s = μW·s × 1000。简化为 mJ ≈ current(μA) * voltage(mV) * duration(s) / 1000。
 * 3. **电量百分比粗估**（兜底）：
 *    用 `batteryPctDelta * totalEnergy / 100` 粗估（需要先知道电池总能量）。
 *
 * 失败时返回 null（不阻塞测试），调用方按"能耗未知"处理。
 *
 * 注：本类能耗估算**不精确**——手机端能耗测量本就是个开放问题，本类只为"横向对比"提供相对值，
 *    不用于"绝对功耗数值"报告。绝对能耗测量需要外部功率计 + USB 电流计。
 *
 * @param context 应用上下文
 */
class EnergyProfiler(private val context: Context) {

    private val tag = "aibox.benchmark.energy"

    /** 在基准测试前调用，记录初始能耗快照。 */
    fun before(): EnergySnapshot {
        val ts = System.currentTimeMillis()
        val battery = readBattery()
        LogSystem.debug(tag, "Energy before: ts=$ts level=${battery.level}% current=${battery.currentMicroAmps}μA voltage=${battery.voltageMilliV}mV")
        return EnergySnapshot(
            timestampMs = ts,
            batteryLevel = battery.level,
            currentMicroAmps = battery.currentMicroAmps,
            voltageMilliV = battery.voltageMilliV
        )
    }

    /** 在基准测试后调用，记录终止能耗快照。 */
    fun after(): EnergySnapshot {
        val ts = System.currentTimeMillis()
        val battery = readBattery()
        LogSystem.debug(tag, "Energy after: ts=$ts level=${battery.level}% current=${battery.currentMicroAmps}μA voltage=${battery.voltageMilliV}mV")
        return EnergySnapshot(
            timestampMs = ts,
            batteryLevel = battery.level,
            currentMicroAmps = battery.currentMicroAmps,
            voltageMilliV = battery.voltageMilliV
        )
    }

    /**
     * 计算两次采样之间的能耗（毫焦耳）。
     *
     * 算法（精确档）：用平均电流 × 平均电压 × 时长秒数。
     *   - 时长 = after.ts - before.ts（秒）
     *   - 平均电流 = (before.current + after.current) / 2 (μA)
     *   - 平均电压 = (before.voltage + after.voltage) / 2 (mV)
     *   - 能量(mJ) = current(μA) * voltage(mV) * duration(s) / 1000
     *
     * 算法（粗估档）：如果电流读不到，用电池电量百分比 × 总能量（典型手机电池 4000mAh × 3.85V ≈ 55.4kJ，
     *   折合 1% ≈ 554J ≈ 554000mJ）反推。
     *
     * 失败（时长 ≤ 0 或所有数据都为 0）时返回 null。
     */
    fun computeMilliJoules(before: EnergySnapshot, after: EnergySnapshot): Long? {
        val durationSec = (after.timestampMs - before.timestampMs).coerceAtLeast(0L) / 1000.0
        if (durationSec <= 0) return null

        // 精确档：电流 × 电压 × 时长
        val cur = listOf(before.currentMicroAmps, after.currentMicroAmps).filter { it > 0 }
        val vol = listOf(before.voltageMilliV, after.voltageMilliV).filter { it > 0 }
        if (cur.isNotEmpty() && vol.isNotEmpty()) {
            val avgCur = cur.average()
            val avgVol = vol.average()
            // mJ = μA × mV × s / 1000
            val mj = (avgCur * avgVol * durationSec / 1000.0).toLong()
            if (mj > 0) return mj
        }

        // 粗估档：电量百分比差 × 单格能量（约 554000mJ / 1%）
        val deltaPct = before.batteryLevel - after.batteryLevel
        if (deltaPct > 0) {
            // 假设电池总能量 ~ 55.4 kJ = 55400000 mJ（典型 4000mAh × 3.85V）
            // 每 1% ≈ 554000 mJ（粗略，按 100% 可用）
            val mjPerPct = 554_000L
            return (deltaPct.toLong() * mjPerPct).coerceAtLeast(0L)
        }

        return null
    }

    // ----------------- 内部 -----------------

    /** 一次性读取电池：电量百分比 / 当前电流 / 电压。 */
    private fun readBattery(): BatteryRead {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter) ?: return BatteryRead()
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

            val currentMicroAmps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: -1
                } catch (_: Throwable) { -1 }
            } else -1

            BatteryRead(
                level = pct,
                currentMicroAmps = currentMicroAmps.toLong(),
                voltageMilliV = voltageMv.toLong()
            )
        } catch (_: Throwable) {
            BatteryRead()
        }
    }

    private data class BatteryRead(
        val level: Int = -1,
        val currentMicroAmps: Long = -1L,   // μA
        val voltageMilliV: Long = -1L       // mV
    )

    /** 能耗采样快照（before / after 各一份）。 */
    data class EnergySnapshot(
        val timestampMs: Long,
        val batteryLevel: Int,
        val currentMicroAmps: Long,
        val voltageMilliV: Long
    )

    /** 尝试初始化（如设备支持 PowerManager 能耗 API），目前多数设备无可 noop。 */
    fun init() {
        // 预留：未来若 PowerManager 暴露 EnergyDelegator，可在这里注册 listener
        // 当前仅记录设备是否支持 BATTERY_PROPERTY_CURRENT_NOW
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val supported = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) != null
            LogSystem.info(tag, "EnergyProfiler init: current_now_supported=$supported")
        } catch (_: Throwable) {
            LogSystem.info(tag, "EnergyProfiler init: current_now_supported=false")
        }
    }
}
