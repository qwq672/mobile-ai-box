package com.aibox.domain.search.providers

import com.aibox.domain.search.SearchDomain
import com.aibox.domain.search.SearchProvider
import com.aibox.domain.search.SearchResult
import com.aibox.domain.search.SearchableEntity
import com.aibox.domain.search.scoreMatch
import com.aibox.util.log.LogSystem

/**
 * 设置搜索 Provider。
 *
 * 数据来源：[SettingRegistry]（静态注册的设置项 metadata）。
 * 搜索维度：
 * - title：设置项标题（如"模型预加载"）
 * - subtitle：description
 * - keywords：path 各级 + key（便于用户搜"TTFT"、"opt.preset" 这种）
 *
 * 跳转信息：
 * - searchData["screen"] = "settings" / "optimization"
 * - searchData["anchor"] = sectionAnchor
 */
class SettingSearchProvider : SearchProvider {

    private val tag = "aibox.search.setting"
    override fun getDomain(): SearchDomain = SearchDomain.SETTING

    override suspend fun search(query: String): List<SearchResult> {
        return try {
            SettingRegistry.all().mapNotNull { entry ->
                val keywords = buildList {
                    add(entry.key)
                    addAll(entry.path)
                }.map { it.lowercase() }

                val match = scoreMatch(
                    query = query,
                    title = entry.title,
                    subtitle = entry.description,
                    keywords = keywords
                ) ?: return@mapNotNull null

                SearchResult(
                    entity = SettingEntityData(
                        searchId = "setting:${entry.key}",
                        searchTitle = entry.title,
                        searchSubtitle = entry.pathDisplay(),
                        searchKeywords = keywords,
                        screen = entry.screen,
                        anchor = entry.sectionAnchor
                    ),
                    score = match.first,
                    matchedField = match.second,
                    matchedSnippet = "${entry.description}（${entry.pathDisplay()}）"
                )
            }
        } catch (t: Throwable) {
            LogSystem.error(tag, "search failed: ${t.message}", t)
            emptyList()
        }
    }
}

/** 设置项实体。 */
private data class SettingEntityData(
    override val searchId: String,
    override val searchTitle: String,
    override val searchSubtitle: String,
    override val searchKeywords: List<String>,
    val screen: String,
    val anchor: String
) : SearchableEntity {
    override val searchDomain: SearchDomain = SearchDomain.SETTING
    override val searchTimestamp: Long? = null
    override val searchData: Map<String, Any> = mapOf(
        "screen" to screen,
        "anchor" to anchor
    )
}
