package com.aibox.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aibox.AIBoxApp
import com.aibox.domain.search.SearchDomain
import com.aibox.domain.search.SearchResult
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 统一搜索 ViewModel。
 *
 * 职责：
 * - 持有 query / 域过滤 / 搜索结果状态
 * - 调用 [com.aibox.domain.search.SearchEngine.search]
 * - 防抖：用户输入后 250ms 才真正发起搜索（避免每次按键都跑全量 Provider）
 *
 * 零异常约定：[SearchEngine] 内部已捕获所有异常；VM 层再加一次防御。
 *
 * UI 状态：[UiState]
 */
class SearchViewModel : ViewModel() {

    private val container get() = AIBoxApp.instance.container
    private val tag = "aibox.ui.search"

    /** UI 全量状态。 */
    data class UiState(
        val query: String = "",
        val selectedDomains: Set<SearchDomain> = SearchDomain.entries.toSet(),
        val results: List<SearchResult> = emptyList(),
        val searching: Boolean = false,
        val hasSearched: Boolean = false,
        /** 单次错误提示；非 null 时 UI 显示 snackbar；UI 处理后调 [clearError] */
        val error: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 防抖任务；用户连续输入时取消上一个，仅保留最后一个。 */
    private var searchJob: Job? = null

    /** 用户输入 query 时调用；防抖 250ms。 */
    fun onQueryChange(q: String) {
        _ui.value = _ui.value.copy(query = q)
        searchJob?.cancel()
        if (q.isBlank()) {
            _ui.value = _ui.value.copy(
                results = emptyList(),
                searching = false,
                hasSearched = false,
                error = null
            )
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            doSearch()
        }
    }

    /** 切换某个域的启用状态；切换后立即重搜。 */
    fun onToggleDomain(domain: SearchDomain) {
        val current = _ui.value.selectedDomains
        val newSet = if (domain in current) current - domain else current + domain
        // 至少保留一个域；用户尝试清空时维持原状
        if (newSet.isEmpty()) {
            _ui.value = _ui.value.copy(error = "至少需选择一个搜索域")
            return
        }
        _ui.value = _ui.value.copy(selectedDomains = newSet, error = null)
        if (_ui.value.query.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch(Dispatchers.IO) { doSearch() }
        }
    }

    /** 立即触发一次搜索（忽略防抖）；UI 上"搜索"按钮调用。 */
    fun onSearchNow() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) { doSearch() }
    }

    /** 清空 query 与结果。 */
    fun onClear() {
        searchJob?.cancel()
        _ui.value = UiState()
    }

    /** UI 处理完错误后调。 */
    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    /** 实际执行搜索；调用 [SearchEngine.search]。 */
    private suspend fun doSearch() {
        val q = _ui.value.query
        val domains = _ui.value.selectedDomains
        _ui.value = _ui.value.copy(searching = true, hasSearched = true, error = null)
        try {
            val results = container.searchEngine.search(q, domains)
            LogSystem.debug(tag, "search q='$q' domains=${domains.size} hits=${results.size}")
            _ui.value = _ui.value.copy(results = results, searching = false)
        } catch (t: Throwable) {
            LogSystem.error(tag, "doSearch failed: ${t.message}", t)
            _ui.value = _ui.value.copy(
                results = emptyList(),
                searching = false,
                error = "搜索失败：${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    companion object {
        /** Compose viewModel() 工厂；让 SearchScreen 不传构造参数即可拿到本 VM。 */
        val Factory = viewModelFactory {
            initializer { SearchViewModel() }
        }
    }
}
