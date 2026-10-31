package com.aibox.domain.search.providers

import com.aibox.domain.search.SearchDomain
import com.aibox.domain.search.SearchProvider
import com.aibox.domain.search.SearchResult

/**
 * 工具搜索 Provider（占位实现）。
 *
 * 真实数据源待 B1 ToolRegistry 实现后接入；当前返回空列表，避免阻塞搜索。
 *
 * 接入步骤（B1 完成后）：
 * 1. 在 [ToolRegistry] 中暴露 `list(): List<ToolDescriptor>`。
 * 2. 本类构造器接收 ToolRegistry 实例。
 * 3. 实现 [search]：调 `ToolRegistry.list()`，把每个 Tool 转成 [SearchableEntity]，
 *    keywords = [toolName, toolCategory, ...]。
 *
 * 当前实现：直接返回空，不抛异常。
 */
class ToolSearchProvider : SearchProvider {

    override fun getDomain(): SearchDomain = SearchDomain.TOOL

    override suspend fun search(query: String): List<SearchResult> {
        // 占位：等 ToolRegistry（B1）实现后接入
        return emptyList()
    }
}
