package com.aibox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.domain.inference.BackendType
import com.aibox.domain.inference.InferenceConfig
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.CpuInfo
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container

    data class UiState(
        val config: InferenceConfig = InferenceConfig(),
        val nativeVersion: String = "",
        val socName: String = "",
        val bigCores: Int = 0,
        val dynamicColor: Boolean = true,
        val darkTheme: String = "system",
        val loggingEnabled: Boolean = true,
        val logFilePath: String? = null,
        val thinkingMode: Boolean = true,
        // 可用后端
        val vulkanAvailable: Boolean = false,
        val npuAvailable: Boolean = false,
        val openclAvailable: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.config.collectLatest { c ->
                _ui.value = _ui.value.copy(config = c)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.dynamicColor.collectLatest {
                _ui.value = _ui.value.copy(dynamicColor = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.darkTheme.collectLatest {
                _ui.value = _ui.value.copy(darkTheme = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.loggingEnabled.collectLatest {
                _ui.value = _ui.value.copy(loggingEnabled = it)
                // 注：旧 AppLogger.setEnabled 已删除；新 LogSystem 通过 setRootLevel 控制级别
            }
        }
        viewModelScope.launch {
            container.settingsRepository.thinkingMode.collectLatest {
                _ui.value = _ui.value.copy(thinkingMode = it)
            }
        }
        viewModelScope.launch {
            runCatching { container.nativeBridge.nativeVersion() }
                .onSuccess { _ui.value = _ui.value.copy(nativeVersion = it) }
                .onFailure {
                    _ui.value = _ui.value.copy(nativeVersion = "native not loaded")
                    LogSystem.error("aibox.ui.settings", "nativeVersion() failed", it)
                }
        }
        _ui.value = _ui.value.copy(
            socName = CpuInfo.socName,
            bigCores = CpuInfo.bigCores,
            logFilePath = LogSystem.getLogDirPath()
        )
        // 探测后端可用性（加日志便于诊断 N/A 问题）
        viewModelScope.launch {
            val b = container.nativeBridge
            val v = runCatching { b.vulkanAvailable() }.getOrDefault(false)
            val n = runCatching { b.npuAvailable() }.getOrDefault(false)
            val o = runCatching { b.openclAvailable() }.getOrDefault(false)
            LogSystem.info("aibox.ui.settings", "SettingsVM backend probe: vulkan=$v npu=$n opencl=$o native=${_ui.value.nativeVersion}")
            _ui.value = _ui.value.copy(
                vulkanAvailable = v,
                npuAvailable    = n,
                openclAvailable = o
            )
        }
    }

    fun update(c: InferenceConfig) {
        viewModelScope.launch { container.settingsRepository.save(c) }
    }

    fun onBackendChange(b: BackendType) {
        update(_ui.value.config.copy(backend = b))
    }

    fun onKvQuantChange(q: String) {
        update(_ui.value.config.copy(kvQuant = q))
    }

    fun onDynamicColorChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDynamicColor(on) }
    }

    fun onDarkThemeChange(mode: String) {
        viewModelScope.launch { container.settingsRepository.setDarkTheme(mode) }
    }

    fun onLoggingEnabledChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setLoggingEnabled(on) }
    }

    fun onThinkingModeChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setThinkingMode(on) }
    }

    fun clearLog() {
        LogSystem.clearAll()
    }
}
