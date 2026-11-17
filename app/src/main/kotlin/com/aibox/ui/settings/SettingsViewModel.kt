package com.aibox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.domain.cloud.CloudConfig
import com.aibox.domain.cloud.CloudPresets
import com.aibox.domain.cloud.CloudProbe
import com.aibox.domain.cloud.CloudProbeError
import com.aibox.domain.cloud.CloudProbeErrorKind
import com.aibox.domain.cloud.CloudProbeFailure
import com.aibox.domain.cloud.CloudProbeResult
import com.aibox.domain.inference.BackendType
import com.aibox.domain.inference.HexagonAssets
import com.aibox.domain.inference.InferenceConfig
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.CpuInfo
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // 思考档位："off" / "low" / "medium" / "high"
        val thinkingLevel: String = "medium",
        // 外观：自定义主色（"dynamic" 或 #RRGGBB）与全局圆角倍率
        val accentColor: String = "dynamic",
        val cornerScale: Float = 1f,
        // 消息呈现：true = 文档流（助手无气泡），false = 双方气泡
        val chatDocStyle: Boolean = true,
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
        val cloudBaseUrl: String = CloudConfig.DEFAULT_BASE_URL,
        val cloudModel: String = CloudConfig.DEFAULT_MODEL,
        val cloudApiKey: String = "",
        // 连接测试 / 模型列表
        val cloudTesting: Boolean = false,
        val cloudTestResult: CloudProbeResult? = null,
        val cloudModels: List<String> = emptyList(),
        val cloudModelPickerOpen: Boolean = false,
        val cloudFetchingModels: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 串行化云端配置写入：输入框逐字触发保存时避免旧值后写入覆盖新值。 */
    private val cloudEditMutex = Mutex()

    /** 配置每次变更都会自增；测试/拉列表返回时据此丢弃过期结果。 */
    private var cloudProbeGeneration = 0

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
            container.settingsRepository.thinkingLevel.collectLatest {
                _ui.value = _ui.value.copy(thinkingLevel = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.accentColor.collectLatest {
                _ui.value = _ui.value.copy(accentColor = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.cornerScale.collectLatest {
                _ui.value = _ui.value.copy(cornerScale = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.chatDocStyle.collectLatest {
                _ui.value = _ui.value.copy(chatDocStyle = it)
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
            // 先部署 HTP skel 并设置 ADSP_LIBRARY_PATH 再探测，否则 hexagon
            // 后端会在缺 skel 的环境里初始化失败并永久缓存 0 设备的结果。
            val n = runCatching {
                withContext(Dispatchers.IO) {
                    val ctx = AIBoxApp.instance
                    val hasSkel = ctx.assets.list("hexagon")?.any { it.endsWith(".so") } == true
                    if (!hasSkel) return@withContext false
                    HexagonAssets.prepare(ctx)?.let { b.setAdspLibraryPath(it) }
                    b.npuAvailable()
                }
            }.getOrDefault(false)
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

    private fun editCloud(transform: (CloudConfig) -> CloudConfig) {
        // 以当前界面值为基准，而不是回读尚未落盘的 DataStore；
        // 再串行写入，避免逐字输入的旧值后写覆盖新值。
        val next = transform(currentCloudConfig())
        cloudProbeGeneration++
        _ui.value = _ui.value.copy(
            cloudEnabled = next.enabled,
            cloudBaseUrl = next.baseUrl,
            cloudModel = next.model,
            cloudApiKey = next.apiKey,
            cloudTestResult = null
        )
        viewModelScope.launch {
            cloudEditMutex.withLock { container.settingsRepository.saveCloud(next) }
        }
    }

    fun onCloudEnabledChange(on: Boolean) {
        val next = currentCloudConfig().copy(enabled = on)
        _ui.value = _ui.value.copy(cloudEnabled = on)
        viewModelScope.launch {
            cloudEditMutex.withLock { container.settingsRepository.saveCloud(next) }
            // 同步到推理引擎：打开后聊天直接走云端，关闭回到本地
            container.inferenceEngine.setCloudEnabled(on)
        }
    }

    fun onCloudBaseUrlChange(v: String) = editCloud { it.copy(baseUrl = v.trim()) }
    fun onCloudModelChange(v: String) = editCloud { it.copy(model = v.trim()) }
    fun onCloudApiKeyChange(v: String) = editCloud { it.copy(apiKey = v.trim()) }

    /** 用当前界面上的配置测连接（两步：模型列表 + 非流式对话）。 */
    fun testCloud() {
        if (_ui.value.cloudTesting) return
        val config = currentCloudConfig()
        val generation = cloudProbeGeneration
        _ui.value = _ui.value.copy(cloudTesting = true, cloudTestResult = null)
        viewModelScope.launch {
            val result = try {
                CloudProbe.probe(config)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                CloudProbeResult(
                    error = CloudProbeError(
                        CloudProbeErrorKind.OTHER,
                        null,
                        (t.message ?: t.javaClass.simpleName).take(300)
                    )
                )
            }
            if (generation != cloudProbeGeneration) {
                // 测试期间配置被改过，这次结果已经过期
                _ui.value = _ui.value.copy(cloudTesting = false)
                return@launch
            }
            _ui.value = _ui.value.copy(
                cloudTesting = false,
                cloudTestResult = result,
                cloudModels = result.models.ifEmpty { _ui.value.cloudModels }
            )
            LogSystem.info("aibox.ui.settings", "cloud probe: ${result.summary}")
        }
    }

    /** 只拉模型列表，失败也写回 [UiState.cloudTestResult] 以展示分类原因。 */
    fun fetchCloudModels() {
        if (_ui.value.cloudFetchingModels) return
        val config = currentCloudConfig()
        val generation = cloudProbeGeneration
        _ui.value = _ui.value.copy(cloudFetchingModels = true, cloudTestResult = null)
        viewModelScope.launch {
            try {
                val models = CloudProbe.listModels(config)
                if (generation != cloudProbeGeneration) {
                    _ui.value = _ui.value.copy(cloudFetchingModels = false)
                    return@launch
                }
                _ui.value = _ui.value.copy(
                    cloudFetchingModels = false,
                    cloudModels = models,
                    cloudModelPickerOpen = models.isNotEmpty()
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val error = (t as? CloudProbeFailure)?.error
                    ?: CloudProbeError(
                        CloudProbeErrorKind.OTHER,
                        null,
                        (t.message ?: "获取模型列表失败").take(300)
                    )
                _ui.value = _ui.value.copy(
                    cloudFetchingModels = false,
                    cloudTestResult = CloudProbeResult(error = error)
                )
            }
        }
    }

    fun onCloudModelSelect(model: String) {
        _ui.value = _ui.value.copy(cloudModelPickerOpen = false)
        editCloud { it.copy(model = model) }
    }

    /** 预设：填充 Base URL 与模型名，并清空测试结果。 */
    fun onCloudPreset(id: String) {
        val preset = CloudPresets.byId(id) ?: return
        _ui.value = _ui.value.copy(
            cloudTestResult = null,
            cloudModels = emptyList(),
            cloudModelPickerOpen = false
        )
        editCloud { it.copy(baseUrl = preset.baseUrl, model = preset.model) }
    }

    private fun currentCloudConfig() = CloudConfig(
        enabled = _ui.value.cloudEnabled,
        baseUrl = _ui.value.cloudBaseUrl,
        apiKey = _ui.value.cloudApiKey,
        model = _ui.value.cloudModel
    )

    fun onDarkThemeChange(mode: String) {
        viewModelScope.launch { container.settingsRepository.setDarkTheme(mode) }
    }

    fun onLoggingEnabledChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setLoggingEnabled(on) }
    }

    fun onThinkingModeChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setThinkingMode(on) }
    }

    fun onThinkingLevelChange(level: String) {
        viewModelScope.launch { container.settingsRepository.setThinkingLevel(level) }
    }

    fun onAccentColorChange(spec: String) {
        viewModelScope.launch {
            if (spec.equals("dynamic", ignoreCase = true)) {
                container.settingsRepository.setDynamicColor(true)
                container.settingsRepository.setAccentColor("dynamic")
            } else {
                // 选具体颜色时自动关闭 Material You，否则壁纸配色会覆盖用户选择
                container.settingsRepository.setDynamicColor(false)
                container.settingsRepository.setAccentColor(spec)
            }
        }
    }

    fun onCornerScaleChange(scale: Float) {
        viewModelScope.launch { container.settingsRepository.setCornerScale(scale) }
    }

    fun onChatDocStyleChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setChatDocStyle(on) }
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
