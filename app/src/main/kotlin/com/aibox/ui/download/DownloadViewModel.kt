package com.aibox.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.download.DownloadPausedException
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

    enum class TaskState { IDLE, RUNNING, PAUSED, DONE, FAILED }

    /** 单个下载任务的状态（UI 行内展示）。 */
    data class DownloadTask(
        val state: TaskState = TaskState.IDLE,
        val percent: Int = 0,
        val downloaded: Long = 0,
        val total: Long = -1,
        val speed: Long = 0
    )

    data class UiState(
        val sources: List<ModelSource> = emptyList(),
        val currentSourceId: String = "hf_mirror",
        val repoInput: String = "",
        val files: List<RemoteGgufFile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        /** 每文件下载状态，key = 仓库内路径 */
        val tasks: Map<String, DownloadTask> = emptyMap(),
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

    /**
     * 每个 key 的会话号：暂停 / 停止 / 重新开始都会递增，
     * 旧协程回来时发现会话号变了就不再改状态（避免“暂停后又被写成失败”）。
     */
    private val sessions = mutableMapOf<String, Int>()

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
        _ui.value = _ui.value.copy(searchResults = emptyList(), files = emptyList(), activeRepo = "")
    }

    /** HF-Mirror.com 失败时是否回退到 hf-mirror.net。 */
    fun setHfMirrorFallback(on: Boolean) {
        viewModelScope.launch { settings.setHfMirrorFallback(on) }
    }

    fun onRepoInput(v: String) { _ui.value = _ui.value.copy(repoInput = v) }

    // ==================== 下载状态 ====================

    private fun destOf(path: String): File =
        File(FileUtils.modelsDir(container.appContext), path.substringAfterLast('/'))

    private fun nextSession(key: String): Int {
        val s = (sessions[key] ?: 0) + 1
        sessions[key] = s
        return s
    }

    private fun isCurrent(key: String, session: Int) = sessions[key] == session

    private fun setTask(key: String, session: Int, transform: (DownloadTask) -> DownloadTask) {
        if (!isCurrent(key, session)) return
        val cur = _ui.value.tasks[key] ?: DownloadTask()
        _ui.value = _ui.value.copy(tasks = _ui.value.tasks + (key to transform(cur)))
    }

    private fun taskOf(key: String): DownloadTask =
        _ui.value.tasks[key] ?: DownloadTask()

    /** 暂停：中断请求，保留已下载的临时文件。 */
    fun pauseDownload(file: RemoteGgufFile) {
        val key = file.path
        val session = nextSession(key)
        container.downloadManager.pause(destOf(key))
        setTask(key, session) { it.copy(state = TaskState.PAUSED, speed = 0) }
    }

    /** 停止：中断请求并删除残留文件。 */
    fun stopDownload(file: RemoteGgufFile) {
        val key = file.path
        val session = nextSession(key)
        val dest = destOf(key)
        container.downloadManager.cancel(dest)
        runCatching { if (dest.exists()) dest.delete() }
        setTask(key, session) { DownloadTask() }
    }

    fun pausePrefab(p: PrefabModel) = pauseDownload(
        RemoteGgufFile(path = p.file, sizeBytes = 0, lastModified = "")
    )

    fun stopPrefab(p: PrefabModel) = stopDownload(
        RemoteGgufFile(path = p.file, sizeBytes = 0, lastModified = "")
    )

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

    // ==================== 下载 ====================

    /** 开始 / 继续下载（已有临时文件时自动续传）。 */
    fun download(repoId: String, file: RemoteGgufFile) {
        val key = file.path
        if (taskOf(key).state == TaskState.RUNNING) return
        val dest = destOf(key)
        val session = nextSession(key)
        setTask(key, session) {
            it.copy(
                state = TaskState.RUNNING,
                percent = if (dest.exists() && dest.length() > 0) it.percent else 0,
                speed = 0
            )
        }

        viewModelScope.launch {
            val primary = container.sourceById(_ui.value.currentSourceId)

            suspend fun tryDownload(src: ModelSource): Result<Unit> = runCatching {
                container.downloadManager.download(
                    url = src.fileUrl(repoId, key),
                    destFile = dest
                ) { p ->
                    setTask(key, session) {
                        it.copy(
                            state = TaskState.RUNNING,
                            percent = p.percent,
                            downloaded = p.bytesDownloaded,
                            total = p.totalBytes,
                            speed = p.bytesPerSecond
                        )
                    }
                }.getOrThrow()
                container.modelRepository.registerDownloaded(
                    filePath = dest.absolutePath,
                    name = dest.nameWithoutExtension
                )
                Unit
            }

            var result = tryDownload(primary)
            val paused = result.exceptionOrNull() is DownloadPausedException
            // hf-mirror.com 失败 → 可选回退到 hf-mirror.net（暂停不算失败）
            if (result.isFailure && !paused && primary.id == "hf_mirror" && _ui.value.hfMirrorFallback) {
                LogSystem.warn(
                    "aibox.ui.download",
                    "DownloadVM: hf-mirror.com failed, retry via hf-mirror.net (${result.exceptionOrNull()?.message})"
                )
                result = tryDownload(container.hfMirrorNetSource)
            }

            result.onSuccess {
                LogSystem.info("aibox.ui.download", "DownloadVM: $key done")
                setTask(key, session) {
                    it.copy(state = TaskState.DONE, percent = 100, speed = 0)
                }
                com.aibox.util.notify.InAppNotifier.show("下载完成：$key")
            }.onFailure { e ->
                if (e is DownloadPausedException) {
                    setTask(key, session) { it.copy(state = TaskState.PAUSED, speed = 0) }
                } else {
                    setTask(key, session) { it.copy(state = TaskState.FAILED, speed = 0) }
                    _ui.value = _ui.value.copy(error = e.message ?: "下载失败")
                }
            }
        }
    }

    /** 一键下载推荐模型 */
    fun downloadPrefab(p: PrefabModel) {
        download(p.repo, RemoteGgufFile(path = p.file, sizeBytes = 0, lastModified = ""))
    }

    fun clearError() { _ui.value = _ui.value.copy(error = null) }
}
