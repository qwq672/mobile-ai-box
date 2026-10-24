package com.aibox.domain.search.providers

import com.aibox.data.db.BenchmarkResultEntity
import com.aibox.domain.search.SearchDomain
import com.aibox.domain.search.SearchProvider
import com.aibox.domain.search.SearchResult
import com.aibox.domain.search.SearchableEntity
import com.aibox.domain.search.scoreMatch
import com.aibox.infra.benchmark.BenchmarkStorage
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 基准测试结果搜索 Provider。
 *
 * 数据来源：[BenchmarkStorage.observeAll] 一次性快照（[Flow.first]）。
 * 搜索维度：
 * - title：模型文件名（从 modelPath 提取）
 * - subtitle：后端名 + TG t/s + 时间
 * - keywords：[modelName, backendName, modelFormat]
 *
 * 注：BenchmarkStorage 不暴露按 q 过滤的查询，本 Provider 在内存中过滤。
 * 历史结果通常 < 1000 条，全量加载 + 内存过滤性能足够。
 */
class BenchmarkSearchProvider(
    private val benchmarkStorage: BenchmarkStorage
) : SearchProvider {

    private val tag = "aibox.search.benchmark"
    override fun getDomain(): SearchDomain = SearchDomain.BENCHMARK

    override suspend fun search(query: String): List<SearchResult> {
        return try {
            val list: List<BenchmarkResultEntity> = benchmarkStorage.observeAll().first()
            list.mapNotNull { e ->
                val modelName = File(e.modelPath).nameWithoutExtension
                val keywords = buildList {
                    add(modelName)
                    add(e.backendName)
                    e.modelFormat?.let { add(it) }
                }.map { it.lowercase() }

                val statusTag = if (e.failed) "失败" else "TG %.1f t/s".format(e.tgTps)
                val subtitle = "$statusTag · ${e.backendName} · ${formatTime(e.timestamp)}"

                val match = scoreMatch(
                    query = query,
                    title = modelName,
                    subtitle = subtitle,
                    keywords = keywords
                ) ?: return@mapNotNull null

                SearchResult(
                    entity = BenchmarkEntityData(
                        searchId = "benchmark:${e.id}",
                        searchTitle = modelName,
                        searchSubtitle = subtitle,
                        searchKeywords = keywords,
                        searchTimestamp = e.timestamp,
                        resultId = e.id,
                        backend = e.backendName,
                        modelPath = e.modelPath,
                        failed = e.failed,
                        tgTps = e.tgTps
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

    private fun formatTime(ts: Long): String {
        if (ts <= 0) return "—"
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            else -> "${diff / 86_400_000} 天前"
        }
    }
}

/** 基准测试结果实体。 */
private data class BenchmarkEntityData(
    override val searchId: String,
    override val searchTitle: String,
    override val searchSubtitle: String,
    override val searchKeywords: List<String>,
    override val searchTimestamp: Long?,
    val resultId: Long,
    val backend: String,
    val modelPath: String,
    val failed: Boolean,
    val tgTps: Double
) : SearchableEntity {
    override val searchDomain: SearchDomain = SearchDomain.BENCHMARK
    override val searchData: Map<String, Any> = mapOf(
        "resultId" to resultId,
        "backend" to backend,
        "modelPath" to modelPath,
        "failed" to failed
    )
}
