package com.pocketllm.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.PocketLLMApp
import com.pocketllm.data.model.ModelInfo
import com.pocketllm.data.repo.SettingsRepository
import com.pocketllm.domain.inference.InferenceConfig
import com.pocketllm.domain.inference.InferenceEngine
import com.pocketllm.util.loge
import com.pocketllm.util.logi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

class ModelsViewModel : ViewModel() {

    private val container get() = PocketLLMApp.instance.container
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
            logi("ModelsViewModel: importing from uri=$uri")
            runCatching { repo.importFromUri(uri) }
                .onFailure {
                    loge("import failed", it)
                    _ui.value = _ui.value.copy(error = "导入失败: ${it.message}")
                }
            _ui.value = _ui.value.copy(importing = false)
        }
    }

    /** 加载（激活）模型；同步展示 loading 和错误 */
    fun activate(id: Long) {
        viewModelScope.launch {
            if (_ui.value.loading) return@launch
            val m = repo.getById(id) ?: run {
                _ui.value = _ui.value.copy(error = "模型不存在 (id=$id)")
                return@launch
            }
            _ui.value = _ui.value.copy(loading = true, error = null)
            logi("ModelsViewModel: activating ${m.name} (${m.filePath})")
            val config = settings.config.firstOrNull() ?: InferenceConfig()
            engine.loadModel(m.filePath, config)
                .onSuccess {
                    settings.setLastModel(id)
                    repo.touchUsed(id)
                    logi("ModelsViewModel: ${m.name} loaded")
                }
                .onFailure {
                    loge("ModelsViewModel: load failed", it)
                    _ui.value = _ui.value.copy(error = "加载失败: ${it.message}")
                }
            _ui.value = _ui.value.copy(loading = false)
        }
    }

    /** 卸载当前模型 */
    fun unload() {
        viewModelScope.launch {
            engine.unload()
            logi("ModelsViewModel: unloaded current model")
            _ui.value = _ui.value.copy(activeId = null, error = null)
        }
    }

    fun delete(m: ModelInfo) {
        viewModelScope.launch {
            // 如果正在激活同一个模型，先卸载
            if (engine.state.value.modelPath == m.filePath) engine.unload()
            logi("ModelsViewModel: deleting ${m.name}")
            runCatching { repo.delete(m) }
                .onFailure { loge("delete failed", it) }
        }
    }

    /** 清除错误提示 */
    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }
}
