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
import kotlinx.coroutines.flow.first
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
        // 设备状态采样周期（ms），设置页可切 1s/2s/5s
        val thermalIntervalMs: Int = 2000,
        // 原生层最近一次加载/解码失败的原因（空串 = 无错误）
        val lastNativeError: String = "",
        // 可用后端
        val vulkanAvailable: Boolean = false,
        val npuAvailable: Boolean = false,
        val openclAvailable: Boolean = false,
        // 云端模型（OpenAI 兼容）
        val cloudEnabled: Boolean = false,
        val cloudBaseUrl: String = com.aibox.domain.cloud.CloudConfig.DEFAULT_BASE_URL,
        val cloudModel: String = com.aibox.domain.cloud.CloudConfig.DEFAULT_MODEL,
        val cloudApiKey: String = ""
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
            container.settingsRepository.cloudConfig.collectLatest { c ->
                _ui.value = _ui.value.copy(
                    cloudEnabled = c.enabled,
                    cloudBaseUrl = c.baseUrl,
                    cloudModel = c.model,
                    cloudApiKey = c.apiKey
                )
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
            logFilePath = LogSystem.getLogDirPath(),
            thermalIntervalMs = container.thermalMonitor.intervalMs.value
        )
        // 诊断信息持续跟随：原生错误可能在加载模型后才出现
        viewModelScope.launch {
            container.thermalMonitor.intervalMs.collectLatest {
                _ui.value = _ui.value.copy(thermalIntervalMs = it)
            }
        }
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

    // ---------- 云端模型 ----------

    private fun editCloud(transform: (com.aibox.domain.cloud.CloudConfig) -> com.aibox.domain.cloud.CloudConfig) {
        viewModelScope.launch {
            val c = container.settingsRepository.cloudConfig.first()
            container.settingsRepository.saveCloud(transform(c))
        }
    }

    fun onCloudEnabledChange(on: Boolean) {
        viewModelScope.launch {
            val c = container.settingsRepository.cloudConfig.first()
            container.settingsRepository.saveCloud(c.copy(enabled = on))
            // 同步到推理引擎：打开后聊天直接走云端，关闭回到本地
            container.inferenceEngine.setCloudEnabled(on)
        }
    }

    fun onCloudBaseUrlChange(v: String) = editCloud { it.copy(baseUrl = v.trim()) }
    fun onCloudModelChange(v: String) = editCloud { it.copy(model = v.trim()) }
    fun onCloudApiKeyChange(v: String) = editCloud { it.copy(apiKey = v.trim()) }

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

    /** 切换设备状态采样周期，立即生效（不需重启 App）。 */
    fun onThermalIntervalChange(ms: Int) {
        container.thermalMonitor.setInterval(ms)
        _ui.value = _ui.value.copy(thermalIntervalMs = container.thermalMonitor.intervalMs.value)
    }

    /** 手动刷新诊断信息：原生版本 + 最近一次原生错误。 */
    fun refreshDiagnostics() {
        val b = container.nativeBridge
        val version = runCatching { b.nativeVersion() }.getOrDefault("native not loaded")
        val err = runCatching { b.nativeLastError() }.getOrDefault("")
        _ui.value = _ui.value.copy(nativeVersion = version, lastNativeError = err)
    }
}
