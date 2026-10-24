package com.aibox.domain.optimization

import com.aibox.domain.inference.BackendManagerV2
import com.aibox.domain.inference.LoadedModel
import com.aibox.domain.inference.OptimizationFlags
import com.aibox.domain.inference.ThermalGovernor
import com.aibox.infra.benchmark.ThermalState
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 能耗优化器（P3）：通用代码层降低能耗。
 *
 * 通用优化（所有模型一视同仁）：
 * - **散热感知降频**（[onThermalStateChanged]）：温度过高时通过 [TgOptimizer.applyToAllWith]
 *   把 `thermalAwareThrottle = true` 的 flags 广播给所有后端，让后端在下次推理前重读热状态。
 *   后端实现可读 [ThermalGovernor] / [com.aibox.util.ThermalMonitor] 自行决定降档策略
 *   （如减少线程、降低 batch、切换到低功耗 backend）；本类不做具体降档，只发"通知"。
 * - **空闲超时卸载模型省电**（[startIdleWatchdog]）：启动后台协程，每分钟检查一次 [lastActivityAt]，
 *   超过 [OptimizationSettings.idleUnloadTimeoutMs] 则把所有 tracked loaded model unload 掉。
 *   调用方（如 ChatViewModel / OptimizationManager）应在每次推理前后调 [recordActivity] 刷新时间戳。
 * - **电池模式提示**（[lowPowerHint]）：返回是否建议进入低功耗档；仅提示，不强制，
 *   调用方据此调用 [TgOptimizer.applyToAllWith] 把 `lowPowerOnBattery = true` 广播给所有后端。
 *
 * 通用约定（P3 核心约束）：
 * - **不针对任何特定模型做特殊优化**：所有模型走相同的"温度高 → flags 通知 → 后端自决降档"路径。
 * - 所有优化都有开关，用户可关闭单个；不依赖自动调优。
 *
 * 与 [ThermalGovernor] 的关系：
 * - V1 [ThermalGovernor] 直接调 `tuneConfig(InferenceConfig)` 改 threads / KV，与 V1 Backend 紧耦合。
 * - V2 抽象层 [InferenceBackendV2] 不暴露 `setThreads`，所以本类不能"直接降线程"；
 *   而是把"thermalAwareThrottle = true"通过 [OptimizationFlags] 传给后端，由后端自行实现降档逻辑。
 *   这保证本类对所有后端通用（包括插件后端），不假设后端有特定 setter。
 *
 * @param thermalGovernor 既有散热监控（与 V1 共享），用于读热档位
 * @param backendManager V2 后端管理器，用于查找 tracked loaded models 与广播 flags
 * @param tgOptimizer TG 优化器，用于 [onThermalStateChanged] 时广播调整后的 flags
 * @param settings 优化设置（snapshot）
 * @param scope 协程作用域，默认 [SupervisorJob] + [Dispatchers.Default]，避免拖累 UI
 */
class EnergyOptimizer(
    private val thermalGovernor: ThermalGovernor,
    private val backendManager: BackendManagerV2,
    private val tgOptimizer: TgOptimizer,
    private val settings: OptimizationSettings,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val tag = "aibox.opt.energy"

    /** 上一次活动时间戳（[System.currentTimeMillis]）。由 [recordActivity] 更新。 */
    @Volatile private var lastActivityAt: Long = System.currentTimeMillis()

    /** 当前热档位状态流，供 UI 显示与外部订阅（如 OptimizationManager 转发给后端）。 */
    private val _thermalState = MutableStateFlow(ThermalState.COOL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    /** idle watchdog 任务。 */
    @Volatile private var watchdogJob: Job? = null

    /** 防止 unload 与新 load 并发的轻量锁。 */
    private val unloadMutex = Mutex()

    /**
     * 启动空闲超时卸载看门狗。
     *
     * - 若 [OptimizationSettings.idleUnloadTimeout] = false 或 [OptimizationSettings.idleUnloadTimeoutMs] <= 0，不启动。
     * - 每 60s 检查一次 [lastActivityAt]；超过 [OptimizationSettings.idleUnloadTimeoutMs] 则
     *   遍历 [BackendManagerV2.listLoadedModels] 调用对应后端 [InferenceBackendV2.unload]，
     *   并 [BackendManagerV2.untrackLoaded] 取消注册。
     * - 任何 unload 失败仅 LogSystem.warn，不抛异常。
     *
     * 幂等：重复调用 [startIdleWatchdog] 不会启动多个 watchdog（先 cancel 旧的）。
     */
    fun startIdleWatchdog() {
        if (!settings.idleUnloadTimeout || settings.idleUnloadTimeoutMs <= 0) {
            LogSystem.info(tag, "idleUnloadTimeout disabled by user or invalid ms=${settings.idleUnloadTimeoutMs}, skip watchdog")
            return
        }
        watchdogJob?.cancel()
        val timeoutMs = settings.idleUnloadTimeoutMs
        LogSystem.info(tag, "starting idle watchdog, timeout=${timeoutMs}ms")
        watchdogJob = scope.launch {
            while (true) {
                delay(CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val idle = now - lastActivityAt
                if (idle < timeoutMs) {
                    continue  // 还有活动
                }
                val loaded = backendManager.listLoadedModels()
                if (loaded.isEmpty()) {
                    continue  // 没有已加载模型，不需要卸载
                }
                LogSystem.info(tag, "idle ${idle}ms > timeout ${timeoutMs}ms, unloading ${loaded.size} model(s)")
                unloadMutex.withLock {
                    loaded.forEach { (backendName, model) ->
                        val backend = backendManager.getBackend(backendName) ?: run {
                            LogSystem.warn(tag, "backend '$backendName' not registered (race), skip unload")
                            return@forEach
                        }
                        runCatching { backend.unload(model) }
                            .onFailure { LogSystem.warn(tag, "unload threw for model '${model.id}': ${it.message}", it) }
                        backendManager.untrackLoaded(backendName, model)
                        LogSystem.info(tag, "unloaded model '${model.id}' from backend '$backendName' due to idle timeout")
                    }
                }
            }
        }
    }

    /** 停止 idle watchdog；用于 App 退出 / 设置变更重新启动。 */
    fun stopIdleWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        LogSystem.info(tag, "idle watchdog stopped")
    }

    /**
     * 记录一次用户活动；调用方应在每次推理前后调用，刷新 idle 计时。
     *
     * 不抛异常；只更新 [lastActivityAt]。
     */
    fun recordActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    /**
     * 散热状态变化时降频。
     *
     * - COOL / WARM：no-op（正常档）。
     * - HOT：把 `thermalAwareThrottle = true` 的 flags 广播给所有后端；后端自行读 [ThermalGovernor] 决定降档力度。
     * - CRITICAL：同 HOT，并额外 LOG 警告建议用户暂停长测。
     *
     * 不实际改 threads / batch（那是 [ThermalGovernor] / 自动调优的职责）；
     * 本方法只发"通知"，让所有后端在下次推理前重读热状态。
     */
    fun onThermalStateChanged(state: ThermalState) {
        _thermalState.value = state
        if (!settings.thermalAwareThrottle) {
            LogSystem.debug(tag, "thermalAwareThrottle disabled, ignore state change: $state")
            return
        }
        when (state) {
            ThermalState.COOL, ThermalState.WARM -> {
                LogSystem.info(tag, "thermal state $state: normal, no throttle needed")
                // 把 thermalAwareThrottle 关掉，让后端恢复正常档（其他 flags 保持当前用户设置）
                tgOptimizer.applyToAllWith(settings.toFlags().copy(thermalAwareThrottle = false))
            }
            ThermalState.HOT -> {
                LogSystem.warn(tag, "thermal state HOT: broadcasting thermalAwareThrottle=true to all backends")
                tgOptimizer.applyToAllWith(settings.toFlags().copy(thermalAwareThrottle = true))
            }
            ThermalState.CRITICAL -> {
                LogSystem.warn(tag, "thermal state CRITICAL: broadcasting thermalAwareThrottle=true (consider pause long benchmarks)")
                tgOptimizer.applyToAllWith(settings.toFlags().copy(thermalAwareThrottle = true))
            }
        }
    }

    /**
     * 当前是否建议进入低功耗档（仅提示，不强制）。
     *
     * - [OptimizationSettings.lowPowerOnBattery] = false → 返回 false。
     * - 否则：检查设备是否在用电池供电（未充电）；是 → true；否 → false。
     *
     * 调用方（如 [OptimizationManager.onAppStart]）可据此决定是否调 [TgOptimizer.applyToAllWith]
     * 把 `lowPowerOnBattery = true` 广播。
     */
    fun lowPowerHint(charging: Boolean): Boolean {
        if (!settings.lowPowerOnBattery) return false
        return !charging
    }

    companion object {
        /** idle watchdog 检查间隔（1 分钟）。 */
        private const val CHECK_INTERVAL_MS = 60_000L
    }
}
