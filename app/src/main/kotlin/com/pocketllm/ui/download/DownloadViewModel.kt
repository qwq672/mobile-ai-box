package com.pocketllm.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.PocketLLMApp
import com.pocketllm.data.repo.SettingsRepository
import com.pocketllm.domain.download.ModelSource
import com.pocketllm.domain.download.RemoteGgufFile
import com.pocketllm.util.FileUtils
import com.pocketllm.util.logi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class DownloadViewModel : ViewModel() {

    private val container get() = PocketLLMApp.instance.container
    private val settings: SettingsRepository get() = container.settingsRepository

    data class UiState(
        val sources: List<ModelSource> = emptyList(),
        val currentSourceId: String = "hf_mirror",
        val repoInput: String = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        val files: List<RemoteGgufFile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val progress: Map<String, Int> = emptyMap()
    )

    /** 一键推荐模型 */
    data class PrefabModel(
        val label: String,
        val repo: String,
        val file: String,
        val note: String
    )

    val prefabs = listOf(
        PrefabModel("Qwen2.5-1.5B-Instruct · Q6_K",
            "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            "qwen2.5-1.5b-instruct-q6_k.gguf",
            "约 1.2 GB · 均衡推荐"),
        PrefabModel("Gemma-3-1B-IT · Q8_0",
            "google/gemma-3-1b-it-GGUF",
            "gemma-3-1b-it-Q8_0.gguf",
            "约 1.1 GB · 小而快"),
        PrefabModel("Qwen2.5-3B-Instruct · Q4_K_M",
            "Qwen/Qwen2.5-3B-Instruct-GGUF",
            "qwen2.5-3b-instruct-q4_k_m.gguf",
            "约 2.0 GB · 更强能力"),
        PrefabModel("LFM2.6B · Q4_K_M",
            "LiquidAI/LFM2.6B-Instruct-GGUF",
            "lfm2.6b-instruct-q4_k_m.gguf",
            "约 1.6 GB · 流式小模型")
    )

    private val _ui = MutableStateFlow(UiState(sources = container.downloadSources))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            settings.downloadSource.collectLatest { src ->
                _ui.value = _ui.value.copy(currentSourceId = src)
            }
        }
    }

    fun selectSource(id: String) {
        viewModelScope.launch { settings.setDownloadSource(id) }
    }

    fun onRepoInput(v: String) { _ui.value = _ui.value.copy(repoInput = v) }

    val listFiles: () -> Unit = {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null, files = emptyList())
            logi("DownloadVM: listing files for ${_ui.value.repoInput}")
            runCatching {
                val src = container.sourceById(_ui.value.currentSourceId)
                src.listGgufFiles(_ui.value.repoInput)
            }.onSuccess { files ->
                _ui.value = _ui.value.copy(loading = false, files = files)
            }.onFailure { e ->
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "未知错误")
            }
        }
        Unit
    }

    fun download(repoId: String, file: RemoteGgufFile) {
        viewModelScope.launch {
            val src = container.sourceById(_ui.value.currentSourceId)
            val url = src.fileUrl(repoId, file.path)
            val destDir = FileUtils.modelsDir(container.appContext)
            val dest = File(destDir, file.path.substringAfterLast('/'))
            logi("DownloadVM: $url -> ${dest.absolutePath}")
            runCatching {
                container.downloadManager.download(url, dest) { p ->
                    _ui.value = _ui.value.copy(
                        progress = _ui.value.progress + (file.path to p)
                    )
                }
            }.onSuccess {
                container.modelRepository.registerDownloaded(
                    filePath = dest.absolutePath,
                    name = dest.nameWithoutExtension
                )
                logi("DownloadVM: ${file.path} done")
            }.onFailure { e ->
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }

    /** 一键下载推荐模型 */
    fun downloadPrefab(p: PrefabModel) {
        download(p.repo, RemoteGgufFile(path = p.file, sizeBytes = 0, lastModified = ""))
    }

    fun clearError() { _ui.value = _ui.value.copy(error = null) }
}
