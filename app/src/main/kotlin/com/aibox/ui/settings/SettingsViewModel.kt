package com.aibox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.domain.inference.BackendType
import com.aibox.domain.inference.HexagonAssets
import com.aibox.domain.inference.InferenceConfig
import com.aibox.infra.jni.NativeBridge
import com.aibox.util.CpuInfo
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
        /** 中性/黑白主题：纯灰阶底色 + 彩色点缀，强制忽略动态取色。 */
        val neutralTheme: Boolean = false,
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
        /** 输入框回车行为：true = 回车发送，false = 回车换行（默认）。 */
        val enterToSend: Boolean = false,
        /** 减弱动态效果：关掉打字机/光标/过渡动画（默认关闭）。 */
        val reduceMotion: Boolean = false,
        /** 云端模型首次建会话时 AI 自动生成标题（默认开启）。 */
        val aiAutoTitle: Boolean = true,
        /** Beta：aibox:choices 选项卡片（默认关闭） */
        val choicesBeta: Boolean = false,
        /** ```html 围栏即时 WebView 预览（默认关闭） */
        val htmlPreview: Boolean = false,
        // 设备状态采样周期（ms），设置页可切 1s/2s/5s
        val thermalIntervalMs: Int = 2000,
        // 原生层最近一次加载/解码失败的原因（空串 = 无错误）
        val lastNativeError: String = "",
        // ---- 实际生效的运行时状态（少黑盒）----
        val runtimeModel: String = "",
        val runtimeBackend: String = "",
        /** 请求的 KV 量化（来自设置）。 */
        val requestedKv: String = "q8_0",
        /** 真正生效的 KV 量化（可能被降级成 f16）。 */
        val effectiveKv: String = "unknown",
        val effectiveFlashAttn: Boolean = false,
        val effectiveThreads: Int = 0,
        val runtimeContextMax: Int = 0,
        // 可用后端
        val vulkanAvailable: Boolean = false,
        val npuAvailable: Boolean = false,
        val openclAvailable: Boolean = false,
        // 云端模型（OpenAI 兼容）
        val cloudEnabled: Boolean = false,
        val cloudBaseUrl: String = com.aibox.domain.cloud.CloudConfig.DEFAULT_BASE_URL,
        val cloudModel: String = com.aibox.domain.cloud.CloudConfig.DEFAULT_MODEL,
        val cloudApiKey: String = "",
        /** 云端输出上限 max_tokens：0=自动（按思考档位给默认值），>0=用户自定义。 */
        val cloudMaxTokens: Int = 0
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.config.collectLatest { c ->
                _ui.value = _ui.value.copy(config = c, requestedKv = c.kvQuant)
            }
        }
        // 运行时状态：模型 / 后端变化后刷新"实际生效"参数
        viewModelScope.launch {
            container.inferenceEngine.state.collectLatest {
                refreshRuntime()
            }
        }
        viewModelScope.launch {
            container.inferenceEngine.stats.collectLatest { s ->
                _ui.value = _ui.value.copy(
                    runtimeBackend = s.backend.displayNameZh,
                    runtimeContextMax = s.contextMax
                )
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
            container.settingsRepository.neutralTheme.collectLatest {
                _ui.value = _ui.value.copy(neutralTheme = it)
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
            container.settingsRepository.enterToSend.collectLatest {
                _ui.value = _ui.value.copy(enterToSend = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.reduceMotion.collectLatest {
                _ui.value = _ui.value.copy(reduceMotion = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.aiAutoTitle.collectLatest {
                _ui.value = _ui.value.copy(aiAutoTitle = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.choicesBeta.collectLatest {
                _ui.value = _ui.value.copy(choicesBeta = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.htmlPreview.collectLatest {
                _ui.value = _ui.value.copy(htmlPreview = it)
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
            container.settingsRepository.cloudMaxTokens.collectLatest {
                _ui.value = _ui.value.copy(cloudMaxTokens = it)
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
                    // 虚拟会话 env 必须在 hexagon backend 枚举设备前设置。
                    // 探测阶段按用户设置设置会话模式（auto 用 native 默认会话数）；
                    // 这样 manual/off 也能在首次探测时生效，而不必等到 load。
                    val opt = runCatching {
                        container.optimizationSettingsRepository.settingsFlow.first()
                    }.getOrNull()
                    if (opt != null) {
                        val count = if (opt.htpSessionMode == com.aibox.domain.power.HtpSessionMode.MANUAL)
                            opt.htpSessionCount else 3
                        runCatching { b.setHexagonNpuSessions(opt.htpSessionMode, count) }
                    }
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
        viewModelScope.launch {
            container.settingsRepository.save(c)
            // 采样参数即时生效：native 的 sampler 只在 load 时构建一次，
            // 之前改设置必须重新加载模型才生效（很黑盒）。这里直接热更新。
            runCatching {
                container.nativeBridge.llamaSetSampler(
                    temperature = c.temperature,
                    topK = c.topK,
                    topP = c.topP,
                    repeatPenalty = c.repeatPenalty,
                    minP = c.minP,
                    repeatLastN = c.repeatLastN
                )
            }.onFailure {
                LogSystem.warn("aibox.ui.settings", "llamaSetSampler hot update failed: ${it.message}")
            }
        }
    }

    fun onBackendChange(b: BackendType) {
        update(_ui.value.config.copy(backend = b))
    }

    /**
     * 统一后端选择：CPU / GPU / NPU。
     *
     * GPU 在配置层仍映射到 Vulkan 或 OpenCL，由 [onGpuUseVulkanChange] 决定。
     */
    fun onUnifiedBackendChange(kind: String) {
        val cfg = _ui.value.config
        val target = when (kind) {
            "gpu" -> if (cfg.gpuUseVulkan) BackendType.VULKAN else BackendType.OPENCL
            "npu" -> BackendType.NPU
            else -> BackendType.CPU
        }
        update(cfg.copy(backend = target))
    }

    /** GPU 后端是否使用 Vulkan（关闭则 OpenCL）；切换后端时立即同步。 */
    fun onGpuUseVulkanChange(on: Boolean) {
        val cfg = _ui.value.config
        update(cfg.copy(gpuUseVulkan = on, backend = if (on) BackendType.VULKAN else BackendType.OPENCL))
    }

    /** FlashAttention 总开关（加载时生效）。 */
    fun onFlashAttentionChange(on: Boolean) {
        update(_ui.value.config.copy(flashAttention = on))
    }

    /** 强制 FlashAttention（加载时生效）。 */
    fun onForceFlashAttentionChange(on: Boolean) {
        update(_ui.value.config.copy(forceFlashAttention = on))
    }

    fun onKvQuantChange(q: String) {
        update(_ui.value.config.copy(kvQuant = q))
    }

    fun onDynamicColorChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDynamicColor(on) }
    }

    /**
     * 中性/黑白主题开关。
     *
     * 联动：开启时一并关闭 Material You 动态取色——中性配色强制忽略动态取色，
     * 提前把它关掉可避免设置页显示"动态取色已开启"的自相矛盾提示。
     * 关闭中性主题时不自动恢复动态取色，尊重用户此前的选择。
     */
    fun onNeutralThemeChange(on: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setNeutralTheme(on)
            if (on) container.settingsRepository.setDynamicColor(false)
        }
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

    /** 云端输出上限：0=自动，>0=自定义（仓库层夹到 1024~393216）。 */
    fun onCloudMaxTokensChange(v: Int) {
        viewModelScope.launch { container.settingsRepository.setCloudMaxTokens(v) }
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

    fun onThinkingLevelChange(level: String) {
        viewModelScope.launch { container.settingsRepository.setThinkingLevel(level) }
    }

    fun onAccentColorChange(spec: String) {
        viewModelScope.launch {
            // 中性主题下动态取色本就无效，不动它，避免开关显示与"已置灰"自相矛盾；
            // 此时选 "dynamic" 仅作为"默认点缀色"（Theme 会回退到 SeedBlue）。
            if (!_ui.value.neutralTheme) {
                if (spec.equals("dynamic", ignoreCase = true)) {
                    container.settingsRepository.setDynamicColor(true)
                } else {
                    // 选具体颜色时自动关闭 Material You，否则壁纸配色会覆盖用户选择
                    container.settingsRepository.setDynamicColor(false)
                }
            }
            container.settingsRepository.setAccentColor(spec)
        }
    }

    fun onCornerScaleChange(scale: Float) {
        viewModelScope.launch { container.settingsRepository.setCornerScale(scale) }
    }

    fun onChatDocStyleChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setChatDocStyle(on) }
    }

    fun onEnterToSendChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setEnterToSend(on) }
    }

    /** 减弱动态效果开关。 */
    fun onReduceMotionChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setReduceMotion(on) }
    }

    /** AI 自动写会话标题开关（仅云端首次建会话生效）。 */
    fun onAiAutoTitleChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAiAutoTitle(on) }
    }

    /** Beta：aibox:choices 选项卡片开关。 */
    fun onChoicesBetaChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setChoicesBeta(on) }
    }

    fun onHtmlPreviewChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setHtmlPreview(on) }
    }

    fun clearLog() {
        LogSystem.clearAll()
    }

    /** 切换设备状态采样周期，立即生效（不需重启 App）。 */
    fun onThermalIntervalChange(ms: Int) {
        container.thermalMonitor.setInterval(ms)
        _ui.value = _ui.value.copy(thermalIntervalMs = container.thermalMonitor.intervalMs.value)
    }

    /** 手动刷新诊断信息：原生版本 + 最近一次原生错误 + 实际生效参数。 */
    fun refreshDiagnostics() {
        val b = container.nativeBridge
        val version = runCatching { b.nativeVersion() }.getOrDefault("native not loaded")
        val err = runCatching { b.nativeLastError() }.getOrDefault("")
        _ui.value = _ui.value.copy(nativeVersion = version, lastNativeError = err)
        refreshRuntime()
    }

    /** 读取"真正生效"的加载参数（KV / FA / 线程 / 模型 / 后端）。 */
    fun refreshRuntime() {
        val b = container.nativeBridge
        val st = container.inferenceEngine.state.value
        _ui.value = _ui.value.copy(
            runtimeModel = st.modelPath?.substringAfterLast('/') ?: "",
            effectiveKv = runCatching { b.llamaEffectiveKv() }.getOrDefault("unknown"),
            effectiveFlashAttn = runCatching { b.llamaEffectiveFlashAttn() }.getOrDefault(false),
            effectiveThreads = runCatching { b.llamaEffectiveThreads() }.getOrDefault(0),
            runtimeContextMax = container.inferenceEngine.stats.value.contextMax
        )
    }

    // ===================================================
    // 重置（按分类 / 全部）
    // ===================================================

    fun resetInference() = viewModelScope.launch {
        runCatching { container.settingsRepository.resetInference() }
            .onFailure { LogSystem.error("aibox.settings", "resetInference failed", it) }
    }

    fun resetAppearance() = viewModelScope.launch {
        runCatching { container.settingsRepository.resetAppearance() }
            .onFailure { LogSystem.error("aibox.settings", "resetAppearance failed", it) }
    }

    fun resetBehavior() = viewModelScope.launch {
        runCatching { container.settingsRepository.resetBehavior() }
            .onFailure { LogSystem.error("aibox.settings", "resetBehavior failed", it) }
    }

    fun resetCloud() = viewModelScope.launch {
        runCatching { container.settingsRepository.resetCloud() }
            .onFailure { LogSystem.error("aibox.settings", "resetCloud failed", it) }
    }

    fun resetToolSettings() = viewModelScope.launch {
        runCatching { container.toolSettingsRepository.resetAll() }
            .onFailure { LogSystem.error("aibox.settings", "resetToolSettings failed", it) }
    }

    fun resetOptimizationSettings() = viewModelScope.launch {
        runCatching { container.optimizationSettingsRepository.reset() }
            .onFailure { LogSystem.error("aibox.settings", "resetOptimizationSettings failed", it) }
    }

    /** 重置所有设置：常规设置 + 优化设置 + 工具设置；保留欢迎页标记与模型列表。 */
    fun resetAllSettings() = viewModelScope.launch {
        runCatching {
            container.settingsRepository.resetAllSettings()
            container.optimizationSettingsRepository.reset()
            container.toolSettingsRepository.resetAll()
            LogSystem.info("aibox.settings", "resetAllSettings: all categories reset")
        }.onFailure { LogSystem.error("aibox.settings", "resetAllSettings failed", it) }
    }
}
