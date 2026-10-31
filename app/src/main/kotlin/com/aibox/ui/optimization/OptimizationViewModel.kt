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

    private val container get() = AIBoxApp.instance.container
    private val repo get() = container.optimizationSettingsRepository

    /** UI 订阅的设置 StateFlow；5s 内无 collector 仍保留缓存，避免旋转屏闪回默认。 */
    val settings: StateFlow<OptimizationSettings> = repo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptimizationSettings.DEFAULT)

    /** 当前预置 ID，UI 顶部下拉显示。 */
    private val _currentPreset = MutableStateFlow("default")
    val currentPreset: StateFlow<String> = _currentPreset.asStateFlow()

    init {
        // 订阅 settings 变化 → 推断 currentPreset
        viewModelScope.launch {
            settings.collectLatest { s ->
                _currentPreset.value = OptimizationSettings.inferPresetId(s)
            }
        }
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
}
