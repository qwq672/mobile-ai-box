package com.aibox.infra.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 电量 / 充电 / 系统省电模式快照。
 *
 * @param level 电量百分比，-1 = 读不到
 * @param charging 是否正在充电（有线 / 无线 / 满电）
 * @param powerSave 系统是否处于省电模式
 */
data class PowerStatus(
    val level: Int = -1,
    val charging: Boolean = false,
    val powerSave: Boolean = false
) {
    /** 电量是否可信（部分模拟器 / 异常设备读不到）。 */
    val levelKnown: Boolean get() = level in 0..100
}

/**
 * 电量监听器。
 *
 * 只在系统广播（电量变化 / 省电模式切换）时更新，不做轮询，避免自己成为耗电源。
 * 通过 sticky intent 读初值，因此启动时即可拿到当前电量。
 */
class BatteryMonitor(context: Context) {

    private val appContext = context.applicationContext

    private val _status = MutableStateFlow(readCurrent())
    val status: StateFlow<PowerStatus> = _status.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    _status.value = parse(intent, _status.value.powerSave)
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    _status.value = _status.value.copy(powerSave = isPowerSaveMode())
                    LogSystem.info(TAG, "power save mode=${_status.value.powerSave}")
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            LogSystem.warn(TAG, "registerReceiver failed: ${it.message}")
        }
        LogSystem.info(TAG, "BatteryMonitor init: level=${_status.value.level} charging=${_status.value.charging}")
    }

    private fun readCurrent(): PowerStatus {
        val sticky = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val fromSticky = if (sticky != null) parse(sticky, isPowerSaveMode()) else PowerStatus(powerSave = isPowerSaveMode())
        return fromSticky.copy(powerSave = isPowerSaveMode())
    }

    private fun parse(intent: Intent, powerSave: Boolean): PowerStatus {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return PowerStatus(level = percent, charging = charging, powerSave = powerSave)
    }

    private fun isPowerSaveMode(): Boolean = runCatching {
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "aibox.power"
    }
}
