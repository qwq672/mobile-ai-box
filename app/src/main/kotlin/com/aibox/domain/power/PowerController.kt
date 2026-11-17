package com.aibox.domain.power

import com.aibox.domain.optimization.OptimizationSettings
import com.aibox.domain.optimization.OptimizationSettingsRepository
import com.aibox.infra.power.BatteryMonitor
import com.aibox.infra.power.PowerStatus
import com.aibox.util.log.LogSystem
import com.aibox.util.notify.InAppNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 电量策略控制器：把电量广播与优化设置合成为「当前档位」，并在切换时记日志。
 *
 * 它只提供决策与可观测性，不直接改任何设置：
 * - 自动档 → 由 UI/加载流程读取 [mode] 再做对应处理
 * - 手动档 → 只用于提示，实际 HTP 档保持用户选择
 *
 * 日志 tag `aibox.power`，方便追溯“为什么这次变慢/变烫”。
 */
class PowerController(
    batteryMonitor: BatteryMonitor,
    settingsRepository: OptimizationSettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    val status: StateFlow<PowerStatus> = batteryMonitor.status

    private val _mode = MutableStateFlow(PowerMode.NORMAL)
    val mode: StateFlow<PowerMode> = _mode.asStateFlow()

    private val _effectiveHtp = MutableStateFlow(HtpPerfMode.PERFORMANCE)
    val effectiveHtp: StateFlow<String> = _effectiveHtp.asStateFlow()

    init {
        scope.launch {
            combine(batteryMonitor.status, settingsRepository.settingsFlow) { s, st -> s to st }
                .collect { (status, settings) -> apply(status, settings) }
        }
    }

    private fun apply(status: PowerStatus, settings: OptimizationSettings) {
        val mode = PowerPolicy.resolve(settings, status)
        val htp = PowerPolicy.effectiveHtp(settings.htpPerfMode, mode)
        val modeChanged = _mode.value != mode
        _mode.value = mode
        _effectiveHtp.value = htp
        if (modeChanged) {
            LogSystem.info(
                "aibox.power",
                "mode=${mode.name} level=${status.level} charging=${status.charging} " +
                    "powerSave=${status.powerSave} htp=$htp configured=${settings.htpPerfMode}"
            )
            // 只提示“进入”低电量，退出时不打扰
            when (mode) {
                PowerMode.SAVER -> InAppNotifier.show("电量较低，已进入平衡模式")
                PowerMode.SURVIVAL -> InAppNotifier.show("电量极低，已进入省电模式")
                PowerMode.NORMAL -> Unit
            }
        }
    }
}
