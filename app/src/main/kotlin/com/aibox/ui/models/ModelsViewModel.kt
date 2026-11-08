package com.aibox.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.model.ModelInfo
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.inference.InferenceConfig
import com.aibox.domain.inference.InferenceEngine
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class ModelsViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val repo get() = container.modelRepository
    private val engine: InferenceEngine get() = container.inferenceEngine
    private val settings: SettingsRepository get() = container.settingsRepository

    data class UiState(
        val models: List<ModelInfo> = emptyList(),
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

    /** 加载（激活）模型；同步展示 loading 和错误。设 90s 超时防止 native 卡死导致 UI 永久 loading */
    fun activate(id: Long) {
        viewModelScope.launch {
            if (_ui.value.loading) return@launch
            val m = repo.getById(id) ?: run {
                _ui.value = _ui.value.copy(error = "模型不存在 (id=$id)")
                return@launch
            }
            _ui.value = _ui.value.copy(loading = true, error = null)
            LogSystem.info("aibox.ui.models", "ModelsViewModel: activating ${m.name} (${m.filePath})")
            val config = settings.config.firstOrNull() ?: InferenceConfig()
            try {
                withTimeoutOrNull(90_000L) { engine.loadModel(m.filePath, config) }
                    ?.onSuccess {
                        settings.setLastModel(id)
                        repo.touchUsed(id)
                        LogSystem.info("aibox.ui.models", "ModelsViewModel: ${m.name} loaded")
                    }
                    ?.onFailure {
                        LogSystem.error("aibox.ui.models", "ModelsViewModel: load failed", it)
                        _ui.value = _ui.value.copy(error = "加载失败: ${it.message}")
                    }
                    ?: run {
                        // 超时:加载超过 90s 未完成,尝试卸载已分配资源并报错
                        LogSystem.error("aibox.ui.models", "ModelsViewModel: load timeout 90s, unloading")
                        try { engine.unload() } catch (_: Throwable) {}
                        _ui.value = _ui.value.copy(error = "加载超时(90s),模型可能过大或后端初始化失败,请尝试切到 CPU 后端或减小上下文")
                    }
            } catch (t: Throwable) {
                LogSystem.error("aibox.ui.models", "ModelsViewModel: activate threw", t)
                _ui.value = _ui.value.copy(error = "加载异常: ${t.message}")
            }
            _ui.value = _ui.value.copy(loading = false)
        }
    }

    /** 卸载当前模型 */
    fun unload() {
        viewModelScope.launch {
            engine.unload()
            LogSystem.info("aibox.ui.models", "ModelsViewModel: unloaded current model")
            _ui.value = _ui.value.copy(activeId = null, error = null)
        }
    }

    fun delete(m: ModelInfo) {
        viewModelScope.launch {
            // 如果正在激活同一个模型，先卸载
            if (engine.state.value.modelPath == m.filePath) engine.unload()
            LogSystem.info("aibox.ui.models", "ModelsViewModel: deleting ${m.name}")
            runCatching { repo.delete(m) }
                .onFailure { LogSystem.error("aibox.ui.models", "delete failed", it) }
        }
    }

    /** 清除错误提示 */
    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }
}
