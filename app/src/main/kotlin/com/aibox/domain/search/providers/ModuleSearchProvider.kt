package com.aibox.domain.search.providers

import com.aibox.domain.search.SearchDomain
import com.aibox.domain.search.SearchProvider
import com.aibox.domain.search.SearchResult
import com.aibox.domain.search.SearchableEntity
import com.aibox.domain.search.scoreMatch
import com.aibox.infra.plugin.PluginInfo
import com.aibox.infra.plugin.PluginManager
import com.aibox.util.log.LogSystem

/**
 * 模块搜索 Provider。
 *
 * 数据来源：[PluginManager.discoverPlugins]（同步扫描已安装 APK 中带 BACKEND_PLUGIN action 的 Service）。
 * 搜索维度：
 * - title：displayName（如"LiteRT-LM"）
 * - subtitle：包名 + 版本 + 体积
 * - keywords：[displayName, packageName, version]
 *
 * 注：discoverPlugins 是同步阻塞调用（PackageManager query），引擎已用 suspend 函数包装，
 * UI 侧应在 IO 调度器调用 SearchEngine.search（ViewModel viewModelScope.launch(Dispatchers.IO)）。
 */
class ModuleSearchProvider(
    private val pluginManager: PluginManager
) : SearchProvider {

    private val tag = "aibox.search.module"
    override fun getDomain(): SearchDomain = SearchDomain.MODULE

    override suspend fun search(query: String): List<SearchResult> {
        return try {
            val plugins: List<PluginInfo> = pluginManager.discoverPlugins()
            val active: Set<String> = pluginManager.listActive().toSet()
            plugins.mapNotNull { p ->
                val keywords = buildList {
                    add(p.displayName)
                    add(p.packageName)
                    if (p.version.isNotBlank()) add(p.version)
                }.map { it.lowercase() }

                val status = if (p.packageName in active) "已启用" else "未启用"
                val sizeMb = if (p.sizeBytes > 0) " · ${p.sizeBytes / 1024 / 1024} MB" else ""

                val match = scoreMatch(
                    query = query,
                    title = p.displayName,
                    subtitle = "$status · ${p.packageName}$sizeMb",
                    keywords = keywords
                ) ?: return@mapNotNull null

                SearchResult(
                    entity = ModuleEntityData(
                        searchId = "module:${p.packageName}",
                        searchTitle = p.displayName,
                        searchSubtitle = "$status · ${p.packageName}$sizeMb",
                        searchKeywords = keywords,
                        packageName = p.packageName,
                        version = p.version,
                        active = p.packageName in active
                    ),
                    score = match.first,
                    matchedField = match.second,
                    matchedSnippet = match.third
                )
            }
        } catch (t: Throwable) {
            LogSystem.error(tag, "search failed: ${t.message}", t)
            emptyList()
        }
    }
}

/** 模块实体。 */
private data class ModuleEntityData(
    override val searchId: String,
    override val searchTitle: String,
    override val searchSubtitle: String,
    override val searchKeywords: List<String>,
    val packageName: String,
    val version: String,
    val active: Boolean
) : SearchableEntity {
    override val searchDomain: SearchDomain = SearchDomain.MODULE
    override val searchTimestamp: Long? = null
    override val searchData: Map<String, Any> = mapOf(
        "packageName" to packageName,
        "version" to version,
        "active" to active
    )
}
