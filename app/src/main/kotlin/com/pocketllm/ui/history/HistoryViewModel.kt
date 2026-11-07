package com.pocketllm.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.PocketLLMApp
import com.pocketllm.data.db.SessionEntity
import com.pocketllm.data.repo.ChatRepository
import com.pocketllm.data.repo.SearchResult
import com.pocketllm.util.logi
import com.pocketllm.util.loge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryViewModel : ViewModel() {

    private val container get() = PocketLLMApp.instance.container
    private val repo: ChatRepository get() = container.chatRepository

    data class UiState(
        val sessions: List<SessionEntity> = emptyList(),
        val searchQuery: String = "",
        val searchMode: Boolean = false,
        val searchResults: List<SearchResult> = emptyList(),
        val loading: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // 监听所有 session
        viewModelScope.launch {
            repo.observeSessions().collectLatest { list ->
                if (!_ui.value.searchMode) {
                    _ui.value = _ui.value.copy(sessions = list)
                }
            }
        }
        // 监听全局消息搜索
        viewModelScope.launch {
            repo.searchAll(_ui.value.searchQuery).collectLatest { results ->
                if (_ui.value.searchMode) {
                    _ui.value = _ui.value.copy(searchResults = results)
                }
            }
        }
    }

    fun onSearchQueryChange(q: String) {
        _ui.value = _ui.value.copy(searchQuery = q, searchMode = q.isNotBlank())
        if (q.isNotBlank()) {
            // 触发 searchAll 重新 emit
            viewModelScope.launch {
                repo.searchAll(q).collectLatest { results ->
                    _ui.value = _ui.value.copy(searchResults = results)
                }
            }
        } else {
            _ui.value = _ui.value.copy(searchResults = emptyList())
            // 重新订阅 sessions
            viewModelScope.launch {
                repo.observeSessions().collectLatest { list ->
                    _ui.value = _ui.value.copy(sessions = list)
                }
            }
        }
    }

    fun createSession(title: String, modelPath: String?, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val id = repo.createSession(title.ifBlank { "新话题" }, modelPath)
                logi("HistoryVM: created session id=$id title=$title")
                id
            }.onSuccess { onCreated(it) }
             .onFailure { loge("HistoryVM: createSession failed", it) }
        }
    }

    fun renameSession(id: Long, newTitle: String) {
        viewModelScope.launch {
            runCatching { repo.updateSessionTitle(id, newTitle) }
                .onFailure { loge("HistoryVM: rename failed", it) }
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            runCatching { repo.deleteSession(id) }
                .onFailure { loge("HistoryVM: delete failed", it) }
        }
    }
}
