package com.aibox.domain.optimization

import com.aibox.domain.inference.InferenceBackendV2
import com.aibox.domain.inference.LoadedModel
import com.aibox.infra.benchmark.ThermalState
import com.aibox.util.ThermalMonitor
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 优化管理器（P3）：统一优化入口，串起 [TtftOptimizer] / [TgOptimizer] / [EnergyOptimizer]。
 *
 * 设计目标：
 * - **单点入口**：App 与各 UI 模块只跟 [OptimizationManager] 打交道，不直接持有三个子 optimizer。
 * - **生命周期托管**：App 启动 → [onAppStart]；用户加载新模型 → [onModelLoaded]；
 *   散热状态变化 → [onThermalStateChanged]；用户改设置 → [onSettingsChanged]。
 * - **设置变化时重新构造子 optimizer**：因为子 optimizer 持有 [OptimizationSettings] snapshot，
 *   改设置后必须重建子 optimizer 才能让新值生效。本类内部用 [settingsStateFlow] 暴露最新 snapshot。
 *
 * 通用约定（P3 核心约束）：
 * - 所有方法通用，对所有模型一视同仁。
 * - 不依赖自动调优；不动 threads / KV 类型 / batch 选择。
 *
 * 与 [com.aibox.di.AppContainer] 的关系：
 * - container 创建 [OptimizationSettingsRepository] 与本 [OptimizationManager] 单例。
 * - 子 optimizer 由本类按最新 settings 重建（每次 settings 变更触发）。
 *
 * @param settingsRepository 优化设置持久化
 * @param thermalMonitor 既有热监控（与 V1 / V2 共享），用于把 sysfs / PowerManager 热档位变化转为 [ThermalState]
 * @param backendManagerV2 V2 后端管理器
 * @param modelRepository 模型仓库
 * @param thermalGovernor 既有散热感知器，传给 [EnergyOptimizer]
 */
class OptimizationManager(
    private val settingsRepository: OptimizationSettingsRepository,
    private val thermalMonitor: ThermalMonitor,
    private val backendManagerV2: com.aibox.domain.inference.BackendManagerV2,
    private val modelRepository: com.aibox.data.repo.ModelRepository,
    private val thermalGovernor: com.aibox.domain.inference.ThermalGovernor
) {
    private val tag = "aibox.opt.manager"

    /** 用于 idle watchdog 与热监控订阅的后台 scope。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前设置 snapshot 流；UI 用 collectAsStateWithLifecycle 收集。 */
    private val _settingsState = MutableStateFlow(OptimizationSettings.DEFAULT)
    val settingsState: StateFlow<OptimizationSettings> = _settingsState.asStateFlow()

    /** 当前设置 Flow（与 repository 同源；UI 直接 collect 这个）。 */
    val currentSettings: Flow<OptimizationSettings> = settingsRepository.settingsFlow

    /** 当前子 optimizer snapshot（随设置变化重建）。 */
    @Volatile private var ttftOpt: TtftOptimizer =
        TtftOptimizer(backendManagerV2, modelRepository, _settingsState.value)
    @Volatile private var tgOpt: TgOptimizer =
        TgOptimizer(backendManagerV2, _settingsState.value)
    @Volatile private var energyOpt: EnergyOptimizer =
        EnergyOptimizer(thermalGovernor, backendManagerV2, tgOpt, _settingsState.value, scope)

    /** 热监控订阅 job；[onAppStart] 启动，[onAppStop] 取消。 */
    @Volatile private var thermalJob: Job? = null

    /** 设置变化订阅 job；[onAppStart] 启动，[onAppStop] 取消。 */
    @Volatile private var settingsJob: Job? = null

    /**
     * App 启动时调用（由 [com.aibox.AIBoxApp.onCreate] 在 container 初始化后调用）。
     *
     * 非阻塞：把"拉初始设置 + 启动订阅 + 启动 watchdog + 异步预加载"全部丢到内部 [scope] 里执行，
     * 主线程调用本方法立即返回。
     *
     * 流程：
     * 1. 拉一次最新设置 → 更新 _settingsState → 重建子 optimizer。
     * 2. 启动 settings 订阅：以后每次设置变化，自动更新 _settingsState + 重建子 optimizer +
     *    广播给所有后端（[TgOptimizer.applyToAll]）。
     * 3. 启动 thermal 订阅：thermalPercent 变化时按 25/60/85 阈值映射为 [ThermalState] → [EnergyOptimizer.onThermalStateChanged]。
     * 4. 启动 idle watchdog。
     * 5. 异步触发 [TtftOptimizer.preloadOnAppStart]。
     *
     * 幂等：重复调用会先 cancel 旧的订阅 / watchdog job 再重启，不会启动多份。
     */
    fun onAppStart() {
        LogSystem.info(tag, "onAppStart requested, launching on internal scope")
        scope.launch {
            // 1. 拉一次初始设置
            val initial = settingsRepository.current()
            _settingsState.value = initial
            rebuildOptimizers(initial)
            LogSystem.info(tag, "onAppStart: initial settings=${OptimizationSettings.inferPresetId(initial)}")

            // 2. 订阅设置变化
            settingsJob?.cancel()
            settingsJob = scope.launch {
                settingsRepository.settingsFlow.collectLatest { s ->
                    if (s != _settingsState.value) {
                        _settingsState.value = s
                        rebuildOptimizers(s)
                        // 广播给所有已注册后端（让它们立即应用新 flags，无需 reload 模型）
                        tgOpt.applyToAll()
                        LogSystem.info(tag, "settings changed, rebuilt optimizers + broadcasted flags")
                        // 设置变化后，idle watchdog 阈值可能改变，重启它
                        energyOpt.startIdleWatchdog()
                    }
                }
            }

            // 3. 订阅热档位变化（thermalMonitor 是 V1 设计，只暴露同步的 thermalPercent()；这里轮询它）
            thermalJob?.cancel()
            thermalJob = scope.launch {
                var lastState: ThermalState? = null
                while (true) {
                    val pct = thermalMonitor.thermalPercent()
                    val state = mapThermalPercent(pct)
                    if (state != lastState) {
                        lastState = state
                        LogSystem.info(tag, "thermal state changed: $lastState -> $state (pct=$pct)")
                        onThermalStateChanged(state)
                    }
                    kotlinx.coroutines.delay(THERMAL_POLL_MS)
                }
            }

            // 4. 启动 idle watchdog
            energyOpt.startIdleWatchdog()

            // 5. 异步预加载模型
            scope.launch { ttftOpt.preloadOnAppStart() }
        }
    }

    /**
     * App 退出时调用（可选；Android App 退出时机不保证，本方法仅做"尽力清理"）。
     */
    fun onAppStop() {
        thermalJob?.cancel()
        settingsJob?.cancel()
        energyOpt.stopIdleWatchdog()
        LogSystem.info(tag, "onAppStop: stopped thermal / settings / watchdog jobs")
    }

    /**
     * 模型加载完成后调用（由 ChatViewModel / BenchmarkViewModel 等调用方在 loadModel 成功后调一次）。
     *
     * - 触发 [TtftOptimizer.warmupAfterLoad] 跑 1 token dummy 推理。
     * - 调 [EnergyOptimizer.recordActivity] 刷新 idle 计时（新加载的模型算"最近活动"）。
     */
    suspend fun onModelLoaded(backend: InferenceBackendV2, model: LoadedModel) {
        energyOpt.recordActivity()
        ttftOpt.warmupAfterLoad(backend, model)
    }

    /**
     * 散热状态变化时调用。
     *
     * 外部调用方（如 [com.aibox.infra.benchmark.EnvironmentMonitor] 也可发状态变化事件）可直接调本方法，
     * 不需等 [thermalMonitor] 的内部轮询触发。
     */
    fun onThermalStateChanged(state: ThermalState) {
        energyOpt.onThermalStateChanged(state)
    }

    /**
     * 记录一次用户活动（如 ChatViewModel 在每次推理前调）。
     */
    fun recordActivity() {
        energyOpt.recordActivity()
    }

    /**
     * 暴露给外部调用方：拿当前 [TtftOptimizer]（用于 streamFirstTokenImmediately 包装 / warmupAfterLoad）。
     */
    fun ttft(): TtftOptimizer = ttftOpt

    /**
     * 暴露给外部调用方：拿当前 [TgOptimizer]（用于 applyBeforeLoad）。
     */
    fun tg(): TgOptimizer = tgOpt

    /**
     * 暴露给外部调用方：拿当前 [EnergyOptimizer]（用于 lowPowerHint / recordActivity）。
     */
    fun energy(): EnergyOptimizer = energyOpt

    // ============== helpers ==============

    /** 用最新设置重建三个子 optimizer。 */
    private fun rebuildOptimizers(s: OptimizationSettings) {
        // 先停旧的 watchdog，避免老的 settings 仍在跑
        energyOpt.stopIdleWatchdog()
        tgOpt = TgOptimizer(backendManagerV2, s)
        ttftOpt = TtftOptimizer(backendManagerV2, modelRepository, s)
        energyOpt = EnergyOptimizer(thermalGovernor, backendManagerV2, tgOpt, s, scope)
    }

    /** 把 thermalMonitor 的 0-100 百分比映射为 [ThermalState]。 */
    private fun mapThermalPercent(pct: Int): ThermalState {
        if (pct < 0) return ThermalState.COOL  // 读不到也按 COOL 处理（避免误降频）
        return when {
            pct < 25 -> ThermalState.COOL
            pct < 60 -> ThermalState.WARM
            pct < 85 -> ThermalState.HOT
            else -> ThermalState.CRITICAL
        }
    }

    companion object {
        /** 热档位轮询间隔（与 [ThermalMonitor] 内部 2s 间隔对齐，但稍长避免频繁 state 切换抖动）。 */
        private const val THERMAL_POLL_MS = 5_000L
    }
}
