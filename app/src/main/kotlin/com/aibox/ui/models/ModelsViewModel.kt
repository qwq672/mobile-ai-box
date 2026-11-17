package com.aibox.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.model.ModelInfo
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.inference.InferenceConfig
import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.InferenceEngine
import com.aibox.domain.inference.LoadedModel
import com.aibox.domain.inference.ModelFormatRouter
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class ModelsViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val repo get() = container.modelRepository
    private val engine: InferenceEngine get() = container.inferenceEngine
    private val settings: SettingsRepository get() = container.settingsRepository

    /** 当前激活的 V2 模型句柄(若走 V2 路径)。V1 路径下为 null。 */
    @Volatile private var activeV2Handle: LoadedModel? = null
    /** 当前激活的 V2 后端名(若走 V2 路径)。 */
    @Volatile private var activeV2BackendName: String? = null

    data class UiState(
        val models: List<ModelInfo> = emptyList(),
        val cloudModels: List<com.aibox.data.repo.CloudModelProfile> = emptyList(),
        val activeCloudId: Long = 0,
        val activeId: Long? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val importing: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAll().collectLatest { list ->
                _ui.value = _ui.value.copy(models = list)
            }
        }
        viewModelScope.launch {
            container.cloudModelRepository.profiles.collectLatest { list ->
                _ui.value = _ui.value.copy(cloudModels = list)
            }
        }
        viewModelScope.launch {
            container.cloudModelRepository.activeId.collectLatest { id ->
                _ui.value = _ui.value.copy(activeCloudId = id)
            }
        }
        viewModelScope.launch {
            engine.state.collectLatest { st ->
                val active = _ui.value.models.firstOrNull { it.filePath == st.modelPath }
                _ui.value = _ui.value.copy(
                    activeId = active?.id,
                    error = if (st.loaded) null else (st.error ?: _ui.value.error)
                )
            }
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(importing = true)
            LogSystem.info("aibox.ui.models", "ModelsViewModel: importing from uri=$uri")
            runCatching { repo.importFromUri(uri) }
                .onFailure {
                    LogSystem.error("aibox.ui.models", "import failed", it)
                    _ui.value = _ui.value.copy(error = "导入失败: ${it.message}")
                }
            _ui.value = _ui.value.copy(importing = false)
        }
    }

    /**
     * 加载(激活)模型。
     * 自动按模型格式路由:
     *  - GGUF/.bin → V1 路径(engine.loadModel)
     *  - .tflite/.litertlm → V2 路径(LiteRT 插件)
     *  - .onnx/.mnn → V2 路径(对应插件)
     * 设 90s 超时防止 native 卡死导致 UI 永久 loading。
     */
    fun activate(id: Long) {
        viewModelScope.launch {
            if (_ui.value.loading) return@launch
            // 切回本地模型时先关云端，否则 completionChat 会继续走云端
            runCatching { engine.setCloudEnabled(false) }
            container.cloudModelRepository.setActive(0)
            val m = repo.getById(id) ?: run {
                _ui.value = _ui.value.copy(error = "模型不存在 (id=$id)")
                return@launch
            }
            _ui.value = _ui.value.copy(loading = true, error = null)
            LogSystem.info("aibox.ui.models", "ModelsViewModel: activating ${m.name} (${m.filePath})")

            // 告诉启动预加载"让路"，别再插一次 load 把用户选择的模型覆盖掉
            com.aibox.domain.optimization.TtftOptimizer.markUserActivated()

            // 卸载上一个(V1 或 V2)
            unloadCurrentSilently()

            val config = settings.config.firstOrNull() ?: InferenceConfig()
            // 推理优化页的「强制 FlashAttention」是唯一需要传入加载流程的 TG 开关：
            // 只有 FA 打开时，量化 KV（q8_0/q4_0）才不会被 native 降级成 f16。
            val opt = runCatching {
                withContext(Dispatchers.IO) { container.optimizationSettingsRepository.settingsFlow.first() }
            }.getOrNull()
            val effectiveConfig = if (opt != null) {
                config.copy(forceFlashAttention = opt.forceFlashAttention)
            } else config
            // 低电量档：只削峰值功率（批大小），不改上下文与 KV，避免静默影响质量。
            val powerMode = container.powerController.mode.value
            val powerCapped = when (powerMode) {
                com.aibox.domain.power.PowerMode.SAVER -> effectiveConfig.copy(
                    physicalBatch = minOf(effectiveConfig.physicalBatch, 256),
                    batch = minOf(effectiveConfig.batch, 1024)
                )
                com.aibox.domain.power.PowerMode.SURVIVAL -> effectiveConfig.copy(
                    physicalBatch = minOf(effectiveConfig.physicalBatch, 128),
                    batch = minOf(effectiveConfig.batch, 512)
                )
                com.aibox.domain.power.PowerMode.NORMAL -> effectiveConfig
            }
            // HTP 档位：当前版本用批大小近似（厂商 DSP 性能接口接入后会直接下发）。
            val htp = container.powerController.effectiveHtp.value
            val loadedConfig = when (htp) {
                com.aibox.domain.power.HtpPerfMode.BALANCED -> powerCapped.copy(
                    physicalBatch = minOf(powerCapped.physicalBatch, 512),
                    batch = minOf(powerCapped.batch, 1024)
                )
                com.aibox.domain.power.HtpPerfMode.POWERSAVE -> powerCapped.copy(
                    physicalBatch = minOf(powerCapped.physicalBatch, 256),
                    batch = minOf(powerCapped.batch, 512)
                )
                else -> powerCapped
            }
            if (loadedConfig != effectiveConfig) {
                LogSystem.info(
                    "aibox.power",
                    "loading: mode=${powerMode.name} htp=$htp physicalBatch=${loadedConfig.physicalBatch} batch=${loadedConfig.batch}"
                )
            }
            // 插件可能刚安装，或启动时的后台同步还没跑完；加载前再同步一次，
            // 避免「已安装 LiteRT 插件却提示未安装」。
            withContext(Dispatchers.IO) {
                runCatching { container.pluginManager.syncInstalledBackendPlugins() }
                    .onFailure { LogSystem.warn("aibox.ui.models", "sync plugins failed: ${it.message}", it) }
            }
            try {
                val route = ModelFormatRouter.route(
                    m.filePath,
                    container.backendManager.listActive()
                )
                when (route) {
                    is ModelFormatRouter.RouteResult.V1Path -> {
                        LogSystem.info("aibox.ui.models", "ModelsVM: route to V1 (format=${route.format})")
                        loadV1(m, loadedConfig, id)
                    }
                    is ModelFormatRouter.RouteResult.V2Path -> {
                        LogSystem.info("aibox.ui.models", "ModelsVM: route to V2 backend '${route.backendName}' (format=${route.format})")
                        loadV2(m, route.backendName, id)
                    }
                    is ModelFormatRouter.RouteResult.NoBackend -> {
                        LogSystem.error("aibox.ui.models", "ModelsVM: no backend for ${route.format}: ${route.reason}")
                        _ui.value = _ui.value.copy(error = route.reason)
                    }
                }
            } catch (t: Throwable) {
                LogSystem.error("aibox.ui.models", "ModelsViewModel: activate threw", t)
                _ui.value = _ui.value.copy(error = "加载异常: ${t.message}")
            } finally {
                // 无论成功/失败/取消都要把 loading 复位，否则第二次点「加载」会被
                // 开头的 `if (loading) return` 直接吞掉，表现就是永远停在「正在加载模型」。
                _ui.value = _ui.value.copy(loading = false)
            }
        }
    }

    /** 走 V1 路径(llama.cpp 内置)加载模型。 */
    private suspend fun loadV1(m: ModelInfo, config: InferenceConfig, id: Long) {
        try {
            withTimeoutOrNull(90_000L) { engine.loadModel(m.filePath, config) }
                ?.onSuccess {
                    settings.setLastModel(id)
                    repo.touchUsed(id)
                    LogSystem.info("aibox.ui.models", "ModelsVM: ${m.name} loaded via V1")
                }
                ?.onFailure {
                    LogSystem.error("aibox.ui.models", "ModelsVM: V1 load failed", it)
                    _ui.value = _ui.value.copy(error = "加载失败: ${it.message}")
                }
                ?: run {
                    LogSystem.error("aibox.ui.models", "ModelsVM: V1 load timeout 90s")
                    try { engine.unload() } catch (_: Throwable) {}
                    _ui.value = _ui.value.copy(error = "加载超时(90s),模型可能过大或后端初始化失败,请尝试切到 CPU 后端或减小上下文")
                }
        } catch (t: Throwable) {
            _ui.value = _ui.value.copy(error = "V1 加载异常: ${t.message}")
        }
    }

    /** 走 V2 路径(插件后端)加载模型。 */
    private suspend fun loadV2(m: ModelInfo, backendName: String, id: Long) {
        try {
            val bm = container.backendManager
            val backend = bm.getBackend(backendName)
            if (backend == null) {
                _ui.value = _ui.value.copy(error = "后端 '$backendName' 不可用(可能插件未启用)")
                return
            }
            val v2Config = InferenceConfigV2(
                taskType = com.aibox.domain.inference.TaskType.TEXT_GENERATION
            )
            val outcome = withContext(Dispatchers.IO) {
                withTimeoutOrNull(90_000L) {
                    backend.loadModel(m.filePath, v2Config)
                }
            } ?: run {
                _ui.value = _ui.value.copy(error = "V2 加载超时(90s),插件可能初始化失败")
                return
            }
            val loaded = outcome.getOrNull()
            if (loaded == null) {
                val reason = outcome.reasonOrNull() ?: "未知错误"
                LogSystem.error("aibox.ui.models", "ModelsVM: V2 load failed: $reason")
                _ui.value = _ui.value.copy(error = "插件加载失败: $reason")
                return
            }
            // 注册到 BackendManager 让 EnergyOptimizer 能管
            bm.trackLoaded(backendName, loaded)
            activeV2Handle = loaded
            activeV2BackendName = backendName
            // 同步到 InferenceEngine 的 V2 状态,让 ChatViewModel 能感知 + 推理
            engine.setV2Active(
                activate = true,
                modelPath = m.filePath,
                backendLabel = backendName,
                delegate = { messages, thinkingMode, onToken, onStop ->
                    runCatchingV2(backend, loaded, messages, thinkingMode, onToken, onStop)
                }
            )
            settings.setLastModel(id)
            repo.touchUsed(id)
            LogSystem.info("aibox.ui.models", "ModelsVM: ${m.name} loaded via V2 backend '$backendName'")
        } catch (t: Throwable) {
            _ui.value = _ui.value.copy(error = "V2 加载异常: ${t.message}")
        }
    }

    /** V2 推理委托(用 V2 后端的 streamInfer 模拟 completionChat)。 */
    private suspend fun runCatchingV2(
        backend: com.aibox.domain.inference.InferenceBackendV2,
        loaded: com.aibox.domain.inference.LoadedModel,
        messages: List<com.aibox.infra.jni.NativeBridge.ChatMsg>,
        thinkingMode: Boolean,
        onToken: (String) -> Unit,
        onStop: (String) -> Unit
    ) {
        try {
            // 把 messages 拼成单条 prompt(简化,不调模型 chat template,由插件自己处理)
            val prompt = buildString {
                for (m in messages) {
                    when (m.role) {
                        "system" -> append("[System] ").append(m.content).append("\n")
                        "user" -> append("[User] ").append(m.content).append("\n")
                        "assistant" -> append("[Assistant] ").append(m.content).append("\n")
                        else -> append(m.content).append("\n")
                    }
                }
            }
            val req = com.aibox.domain.inference.InferenceRequest(
                taskId = java.util.UUID.randomUUID().toString(),
                taskType = com.aibox.domain.inference.TaskType.TEXT_GENERATION,
                modelId = loaded.id,
                inputs = listOf(com.aibox.domain.inference.ModalityInput.Text(prompt)),
                config = loaded.config,
                stream = true
            )
            backend.streamInfer(req, loaded).collect { chunk ->
                val text = (chunk.partial as? com.aibox.domain.inference.ModalityOutput.Text)?.content.orEmpty()
                if (text.isNotEmpty()) onToken(text)
                if (chunk.done) {
                    onStop("stop")
                }
            }
        } catch (t: Throwable) {
            com.aibox.util.log.LogSystem.error("aibox.ui.models", "V2 inference threw", t)
            onStop("exception: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 静默卸载当前激活的模型(V1 或 V2),不抛错。 */
    private suspend fun unloadCurrentSilently() = withContext(Dispatchers.IO) {
        // V2 卸载
        val v2BackendName = activeV2BackendName
        val v2Handle = activeV2Handle
        if (v2BackendName != null && v2Handle != null) {
            try {
                val backend = container.backendManager.getBackend(v2BackendName)
                backend?.unload(v2Handle)
                container.backendManager.untrackLoaded(v2BackendName, v2Handle)
            } catch (t: Throwable) {
                LogSystem.warn("aibox.ui.models", "unloadCurrentSilently: V2 unload failed: ${t.message}")
            }
            activeV2Handle = null
            activeV2BackendName = null
            // 清空 InferenceEngine 的 V2 状态
            engine.setV2Active(activate = false)
        }
        // V1 卸载
        try {
            if (engine.state.value.loaded) engine.unload()
        } catch (t: Throwable) {
            LogSystem.warn("aibox.ui.models", "unloadCurrentSilently: V1 unload failed: ${t.message}")
        }
        // 启动预加载可能绕过本 VM 直接调了 V1BackendAdapter，registry 里会留下
        // 指向已被释放的 native 模型的句柄；这里统一清掉，避免后续误用。
        runCatching {
            container.backendManager.listLoadedModels().forEach { (name, model) ->
                container.backendManager.untrackLoaded(name, model)
            }
        }
    }

    /** 卸载当前模型 */
    fun unload() {
        viewModelScope.launch {
            unloadCurrentSilently()
            LogSystem.info("aibox.ui.models", "ModelsViewModel: unloaded current model")
            _ui.value = _ui.value.copy(activeId = null, error = null)
        }
    }

    fun delete(m: ModelInfo) {
        viewModelScope.launch {
            // 如果正在激活同一个模型,先卸载
            val activePath = activeV2Handle?.modelPath ?: engine.state.value.modelPath
            if (activePath == m.filePath) unloadCurrentSilently()
            LogSystem.info("aibox.ui.models", "ModelsViewModel: deleting ${m.name}")
            runCatching { repo.delete(m) }
                .onFailure { LogSystem.error("aibox.ui.models", "delete failed", it) }
        }
    }

    /** 清除错误提示 */
    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    // ===================================================
    // 云端模型（作为独立“模型”）
    // ===================================================

    /** 选中一个云端模型：先卸载本地模型，再写入配置并启用云端。 */
    fun activateCloud(profile: com.aibox.data.repo.CloudModelProfile) {
        viewModelScope.launch {
            if (_ui.value.loading) return@launch
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                unloadCurrentSilently()
                settings.saveCloud(container.cloudModelRepository.toConfig(profile, enabled = true))
                engine.setCloudEnabled(true)
                container.cloudModelRepository.setActive(profile.id)
                LogSystem.info("aibox.ui.models", "cloud model activated: ${profile.name} (${profile.model})")
            } catch (t: Throwable) {
                LogSystem.error("aibox.ui.models", "activateCloud failed", t)
                _ui.value = _ui.value.copy(error = "云端模型启用失败: ${t.message}")
            } finally {
                _ui.value = _ui.value.copy(loading = false)
            }
        }
    }

    fun deleteCloud(profile: com.aibox.data.repo.CloudModelProfile) {
        viewModelScope.launch {
            runCatching {
                container.cloudModelRepository.delete(profile.id)
                if (_ui.value.activeCloudId == profile.id) {
                    engine.setCloudEnabled(false)
                }
            }.onFailure { LogSystem.warn("aibox.ui.models", "deleteCloud failed: ${it.message}") }
        }
    }
}
