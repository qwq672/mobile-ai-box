package com.aibox.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.download.ModelSource
import com.aibox.domain.download.RemoteGgufFile
import com.aibox.domain.download.RemoteModelSummary
import com.aibox.util.FileUtils
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class DownloadViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val settings: SettingsRepository get() = container.settingsRepository

    data class UiState(
        val sources: List<ModelSource> = emptyList(),
        val currentSourceId: String = "hf_mirror",
        val repoInput: String = "",
        val files: List<RemoteGgufFile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val progress: Map<String, Int> = emptyMap(),
        /** HF-Mirror.com 下载失败时是否自动改用 hf-mirror.net */
        val hfMirrorFallback: Boolean = true,
        // ===== 搜索 =====
        val searchQuery: String = "",
        val searchResults: List<RemoteModelSummary> = emptyList(),
        val searching: Boolean = false,
        /** 当前展开文件列表的仓库（空 = 还在搜索结果页） */
        val activeRepo: String = "",
        /** 当前下载源是否支持关键词搜索 */
        val searchSupported: Boolean = true
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
                _ui.value = _ui.value.copy(
                    currentSourceId = src,
                    // 魔搭没有公开的模型搜索接口，只能按仓库 ID 下载
                    searchSupported = src != "modelscope"
                )
            }
        }
        viewModelScope.launch {
            settings.hfMirrorFallback.collectLatest { on ->
                _ui.value = _ui.value.copy(hfMirrorFallback = on)
            }
        }
    }

    fun selectSource(id: String) {
        viewModelScope.launch { settings.setDownloadSource(id) }
        // 换源后旧搜索结果不再可信
        _ui.value = _ui.value.copy(searchResults = emptyList(), files = emptyList(), activeRepo = "")
    }

    /** HF-Mirror.com 失败时是否回退到 hf-mirror.net。 */
    fun setHfMirrorFallback(on: Boolean) {
        viewModelScope.launch { settings.setHfMirrorFallback(on) }
    }

    fun onRepoInput(v: String) { _ui.value = _ui.value.copy(repoInput = v) }

    // ==================== 搜索 ====================

    fun onSearchQuery(v: String) { _ui.value = _ui.value.copy(searchQuery = v) }

    fun search() {
        val q = _ui.value.searchQuery.trim()
        if (q.isEmpty()) return
        if (!_ui.value.searchSupported) {
            _ui.value = _ui.value.copy(error = "魔搭暂不支持关键词搜索，请直接输入仓库 ID 后下载")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(searching = true, error = null, searchResults = emptyList(), activeRepo = "")
            LogSystem.info("aibox.ui.download", "DownloadVM: search '$q' on ${_ui.value.currentSourceId}")
            runCatching {
                container.sourceById(_ui.value.currentSourceId).searchModels(q)
            }.onSuccess { list ->
                _ui.value = _ui.value.copy(searching = false, searchResults = list)
            }.onFailure { e ->
                _ui.value = _ui.value.copy(searching = false, error = e.message ?: "搜索失败")
            }
        }
    }

    /** 打开某个仓库（列出其 GGUF 文件）。 */
    fun openRepo(repo: String) {
        _ui.value = _ui.value.copy(repoInput = repo, activeRepo = repo)
        listFiles()
    }

    /** 退出文件列表，回到搜索结果。 */
    fun closeRepo() {
        _ui.value = _ui.value.copy(activeRepo = "", files = emptyList())
    }

    val listFiles: () -> Unit = {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null, files = emptyList())
            LogSystem.info("aibox.ui.download", "DownloadVM: listing files for ${_ui.value.repoInput}")
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
            val primary = container.sourceById(_ui.value.currentSourceId)
            val destDir = FileUtils.modelsDir(container.appContext)
            val dest = File(destDir, file.path.substringAfterLast('/'))
            val report: (Int) -> Unit = { p ->
                _ui.value = _ui.value.copy(progress = _ui.value.progress + (file.path to p))
            }

            suspend fun tryDownload(src: ModelSource): Result<Unit> = runCatching {
                val url = src.fileUrl(repoId, file.path)
                LogSystem.info("aibox.ui.download", "DownloadVM: $url -> ${dest.absolutePath}")
                container.downloadManager.download(url, dest, report).getOrThrow()
                container.modelRepository.registerDownloaded(
                    filePath = dest.absolutePath,
                    name = dest.nameWithoutExtension
                )
                Unit
            }

            var result = tryDownload(primary)
            // hf-mirror.com 失败 → 可选回退到 hf-mirror.net
            if (result.isFailure && primary.id == "hf_mirror" && _ui.value.hfMirrorFallback) {
                LogSystem.warn(
                    "aibox.ui.download",
                    "DownloadVM: hf-mirror.com failed, retry via hf-mirror.net (${result.exceptionOrNull()?.message})"
                )
                result = tryDownload(container.hfMirrorNetSource)
            }

            result.onSuccess {
                LogSystem.info("aibox.ui.download", "DownloadVM: ${file.path} done")
            }.onFailure { e ->
                _ui.value = _ui.value.copy(error = e.message ?: "下载失败")
            }
        }
    }

    /** 一键下载推荐模型 */
    fun downloadPrefab(p: PrefabModel) {
        download(p.repo, RemoteGgufFile(path = p.file, sizeBytes = 0, lastModified = ""))
    }

    fun clearError() { _ui.value = _ui.value.copy(error = null) }
}
