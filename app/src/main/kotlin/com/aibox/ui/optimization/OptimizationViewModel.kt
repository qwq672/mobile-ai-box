package com.aibox.ui.optimization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.domain.optimization.OptimizationSettings
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 优化设置页 ViewModel（P3）。
 *
 * 持有：
 * - [settings]：从 [com.aibox.domain.optimization.OptimizationSettingsRepository.settingsFlow] 转换而来的
 *   StateFlow（`stateIn(viewModelScope, WhileSubscribed(5000), DEFAULT)`），保证配置变化时 UI 自动重组。
 * - [currentPreset]：当前设置最接近的预置 ID（"default" / "performance" / "power_save" / "custom"）。
 *
 * 提供：
 * - [update]：原子修改某个字段（`it.copy(...)`）。
 * - [applyPreset]：一键应用预置（覆盖整份设置）。
 * - [resetToDefault]：重置为默认档。
 *
 * 设计：
 * - UI 调 [update] 时仅传 `transform`，由 ViewModel 内部走 repository.update 通道，保证 DataStore 串行化。
 * - 不直接持有 [OptimizationManager]；UI 通过 [com.aibox.AIBoxApp.container] 拿到后直接调
 *   [com.aibox.domain.optimization.OptimizationManager.onThermalStateChanged] 等方法（如果需要），
 *   不经过本 ViewModel。本 VM 只管设置读写。
 */
class OptimizationViewModel : ViewModel() {

    /** 运行时实际生效的快照（少黑盒）：模型、后端、KV、FA、线程、上下文。 */
    data class RuntimeInfo(
        val model: String = "",
        val backend: String = "",
        val kv: String = "unknown",
        val flashAttn: Boolean = false,
        val threads: Int = 0,
        val contextMax: Int = 0
    )

    private val container get() = AIBoxApp.instance.container
    private val repo get() = container.optimizationSettingsRepository

    /** UI 订阅的设置 StateFlow；5s 内无 collector 仍保留缓存，避免旋转屏闪回默认。 */
    val settings: StateFlow<OptimizationSettings> = repo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptimizationSettings.DEFAULT)

    private val _runtime = MutableStateFlow(RuntimeInfo())
    val runtime: StateFlow<RuntimeInfo> = _runtime.asStateFlow()

    /** 当前预置 ID，UI 顶部下拉显示。 */
    private val _currentPreset = MutableStateFlow("default")
    val currentPreset: StateFlow<String> = _currentPreset.asStateFlow()

    /** 电量 / 充电 / 系统省电模式。 */
    val powerStatus: StateFlow<com.aibox.infra.power.PowerStatus> = container.batteryMonitor.status

    /** 当前电量档位（正常 / 省电 / 极低电量）。 */
    val powerMode: StateFlow<com.aibox.domain.power.PowerMode> = container.powerController.mode

    /** 当前实际生效的 HTP 档（自动档会按电量解析）。 */
    val effectiveHtp: StateFlow<String> = container.powerController.effectiveHtp

    /** 低电量轻提示是否允许显示。 */
    val warnLowBattery: StateFlow<Boolean> = container.settingsRepository.warnLowBattery
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 极低电量警告是否允许显示。 */
    val warnCriticalBattery: StateFlow<Boolean> = container.settingsRepository.warnCriticalBattery
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        // 订阅 settings 变化 → 推断 currentPreset
        viewModelScope.launch {
            settings.collectLatest { s ->
                _currentPreset.value = OptimizationSettings.inferPresetId(s)
            }
        }
        // 模型 / 后端变化 → 刷新实际生效参数
        viewModelScope.launch {
            container.inferenceEngine.state.collectLatest { refreshRuntime() }
        }
        viewModelScope.launch {
            container.inferenceEngine.stats.collectLatest {
                _runtime.value = _runtime.value.copy(
                    backend = it.backend.displayNameZh,
                    contextMax = it.contextMax
                )
            }
        }
    }

    /** 读取 native 侧真正生效的加载参数。 */
    fun refreshRuntime() {
        val b = container.nativeBridge
        val st = container.inferenceEngine.state.value
        _runtime.value = _runtime.value.copy(
            model = st.modelPath?.substringAfterLast('/') ?: "",
            kv = runCatching { b.llamaEffectiveKv() }.getOrDefault("unknown"),
            flashAttn = runCatching { b.llamaEffectiveFlashAttn() }.getOrDefault(false),
            threads = runCatching { b.llamaEffectiveThreads() }.getOrDefault(0),
            contextMax = container.inferenceEngine.stats.value.contextMax
        )
    }

    /**
     * 原子更新某个字段。
     *
     * 用法：
     * ```
     * vm.update { it.copy(forceFlashAttention = false) }
     * vm.update { it.copy(idleUnloadTimeoutMs = 5 * 60 * 1000) }
     * ```
     */
    fun update(transform: (OptimizationSettings) -> OptimizationSettings) {
        viewModelScope.launch {
            runCatching { repo.update(transform) }
                .onFailure { LogSystem.error("aibox.ui.opt", "update failed: ${it.message}", it) }
        }
    }

    /**
     * 应用预置（覆盖整份设置）。
     *
     * @param presetId "default" / "performance" / "power_save"
     */
    fun applyPreset(presetId: String) {
        val preset = OptimizationSettings.presetById(presetId) ?: run {
            LogSystem.warn("aibox.ui.opt", "unknown preset id: $presetId")
            return
        }
        viewModelScope.launch {
            runCatching { repo.set(preset) }
                .onSuccess { LogSystem.info("aibox.ui.opt", "applied preset: $presetId") }
                .onFailure { LogSystem.error("aibox.ui.opt", "applyPreset failed: ${it.message}", it) }
        }
    }

    /** 重置为默认档（等同 [applyPreset] "default"，独立按钮语义清晰）。 */
    fun resetToDefault() = applyPreset("default")

    /** 设置 HTP 性能档："auto" / "performance" / "balanced" / "powersave"。 */
    fun setHtpPerfMode(id: String) {
        LogSystem.info("aibox.power", "user set htpPerfMode=$id")
        update { it.copy(htpPerfMode = id) }
    }

    /** 设置 Hexagon 虚拟 NPU 会话模式："auto" / "manual" / "off"。重新加载模型生效。 */
    fun setHtpSessionMode(id: String) {
        LogSystem.info("aibox.hexagon", "user set htpSessionMode=$id")
        update { it.copy(htpSessionMode = id) }
    }

    /** 设置手动虚拟会话数（1-8）。仅在 htpSessionMode=manual 时生效。 */
    fun setHtpSessionCount(count: Int) {
        update { it.copy(htpSessionCount = count.coerceIn(1, 8)) }
    }

    /** 设置低电量模式开关（只影响自动提示与后台策略，不改手动 HTP 档）。 */
    fun setLowPowerMode(on: Boolean) = update { it.copy(lowPowerMode = on) }

    /** 设置进入低电量模式的阈值（%）。 */
    fun setLowPowerThreshold(percent: Int) =
        update { it.copy(lowPowerThreshold = percent.coerceIn(5, 50)) }

    /** 永久开启 / 关闭低电量轻提示。 */
    fun setWarnLowBattery(on: Boolean) {
        viewModelScope.launch {
            runCatching { container.settingsRepository.setWarnLowBattery(on) }
                .onFailure { LogSystem.warn("aibox.power", "setWarnLowBattery failed: ${it.message}") }
        }
    }

    /** 永久开启 / 关闭极低电量警告。 */
    fun setWarnCriticalBattery(on: Boolean) {
        viewModelScope.launch {
            runCatching { container.settingsRepository.setWarnCriticalBattery(on) }
                .onFailure { LogSystem.warn("aibox.power", "setWarnCriticalBattery failed: ${it.message}") }
        }
    }
}
