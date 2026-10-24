package com.aibox.domain.search.providers

import com.aibox.data.model.ModelInfo
import com.aibox.data.repo.ModelRepository
import com.aibox.domain.search.SearchDomain
import com.aibox.domain.search.SearchProvider
import com.aibox.domain.search.SearchResult
import com.aibox.domain.search.SearchableEntity
import com.aibox.domain.search.scoreMatch
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.first

/**
 * 模型搜索 Provider。
 *
 * 数据来源：[ModelRepository.observeAll] 一次性快照（[Flow.first]）。
 * 搜索维度：
 * - title：模型用户名（如 "Qwen2.5-0.5B-Instruct"）
 * - subtitle：模型路径 + 大小
 * - keywords：[name, arch, quant, params] 全部小写
 *
 * 评分通过 [scoreMatch]；arch / quant / params 命中归到 keywords。
 */
class ModelSearchProvider(
    private val modelRepository: ModelRepository
) : SearchProvider {

    private val tag = "aibox.search.model"
    override fun getDomain(): SearchDomain = SearchDomain.MODEL

    override suspend fun search(query: String): List<SearchResult> {
        return try {
            val models = modelRepository.observeAll().first()
            models.mapNotNull { m ->
                val keywords = buildList {
                    add(m.name)
                    m.arch?.let { add(it) }
                    m.quant?.let { add(it) }
                    m.params?.let { add(it) }
                }.map { it.lowercase() }

                val match = scoreMatch(
                    query = query,
                    title = m.name,
                    subtitle = "${m.arch ?: "—"} · ${m.quant ?: "—"} · ${m.params ?: "—"} · ${m.sizeMb} MB",
                    keywords = keywords
                ) ?: return@mapNotNull null

                SearchResult(
                    entity = ModelEntityData(
                        searchId = "model:${m.id}",
                        searchTitle = m.name,
                        searchSubtitle = "${m.arch ?: "—"} · ${m.quant ?: "—"} · ${m.params ?: "—"} · ${m.sizeMb} MB",
                        searchKeywords = keywords,
                        searchTimestamp = m.lastUsedAt.takeIf { it > 0 } ?: m.addedAt,
                        modelId = m.id,
                        modelPath = m.filePath
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

/** 模型实体。 */
private data class ModelEntityData(
    override val searchId: String,
    override val searchTitle: String,
    override val searchSubtitle: String,
    override val searchKeywords: List<String>,
    override val searchTimestamp: Long?,
    val modelId: Long,
    val modelPath: String
) : SearchableEntity {
    override val searchDomain: SearchDomain = SearchDomain.MODEL
    override val searchData: Map<String, Any> = mapOf(
        "modelId" to modelId,
        "modelPath" to modelPath
    )
}
