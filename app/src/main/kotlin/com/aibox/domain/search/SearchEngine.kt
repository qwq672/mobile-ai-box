package com.aibox.domain.search

import com.aibox.util.log.LogSystem

/**
 * 统一搜索引擎：聚合多个 [SearchProvider]，按 [query] 跨域搜索，按 score 排序返回。
 *
 * 设计原则：
 * - **零异常约定**：任一 Provider 抛异常，本引擎捕获后返回空结果不中断其他 Provider。
 *   异常通过 [LogSystem.error] 记录。
 * - **域过滤**：调用方可指定 [domains] 集合（默认全部），仅调度匹配域的 Provider。
 * - **结果排序**：按 [SearchResult.score] 降序；同分时按 [SearchableEntity.searchTimestamp] 降序。
 * - **高亮片段**：Provider 在 [SearchResult.matchedSnippet] 中应包含 query 周围上下文，
 *   UI 层可基于 query 给 snippet 中匹配子串加底色。
 *
 * 使用示例：
 * ```
 * val engine = SearchEngineFactory.create(container)
 * val results = engine.search("qwen", domains = setOf(SearchDomain.MODEL, SearchDomain.CHAT_MESSAGE))
 * ```
 *
 * @param providers 已注册的 Provider 列表；顺序不影响最终排序（最终按 score 排序）。
 */
class SearchEngine(
    private val providers: List<SearchProvider>
) {

    private val tag = "aibox.search"

    /**
     * 执行搜索。
     *
     * @param query 查询字符串；空白直接返回空列表，不调任何 Provider。
     * @param domains 限定搜索的域集合；默认 [SearchDomain.values] 全部。
     * @return 按 score 降序的结果列表；同分按时间戳降序。
     */
    suspend fun search(
        query: String,
        domains: Set<SearchDomain> = SearchDomain.entries.toSet()
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        LogSystem.debug(tag, "search q='$q' domains=${domains.size}/${providers.size}")

        val activeProviders = providers.filter { it.getDomain() in domains }
        if (activeProviders.isEmpty()) {
            LogSystem.debug(tag, "no provider matches domains=$domains")
            return emptyList()
        }

        // 串行调用各 Provider（避免一次搜索触发 7 个 Provider 并发抢锁；search 通常 < 50ms）。
        // 注：flatMap 的 lambda 不是 suspend，所以用 mutableListOf + addAll 显式循环。
        val results = mutableListOf<SearchResult>()
        for (provider in activeProviders) {
            try {
                results.addAll(provider.search(q))
            } catch (t: Throwable) {
                // 零异常约定：单 Provider 失败不中断整体搜索
                LogSystem.error(tag, "Provider ${provider.getDomain()} failed: ${t.message}", t)
            }
        }

        // 排序：score 降序为主，同分按时间戳降序（null 时间戳排后）
        return results.sortedWith(
            compareByDescending<SearchResult> { it.score }
                .thenByDescending { it.entity.searchTimestamp ?: -1L }
        )
    }

    /** 列出所有已注册 Provider 的域；UI 用于判断哪些 FilterChip 应该可见。 */
    fun availableDomains(): Set<SearchDomain> = providers.map { it.getDomain() }.toSet()
}

/**
 * 搜索 Provider 接口：每个域实现一个，向 [SearchEngine] 提供搜索能力。
 *
 * - **getDomain** 返回该 Provider 负责的 [SearchDomain]；一个 Provider 只能负责一个域
 *   （避免异常时影响多个域）。
 * - **search** 接收已 trim + lowercase 的 query，返回零或多条 [SearchResult]。
 *   实现应做好异常隔离（内部 try-catch），即便如此 [SearchEngine] 仍会在外层兜底。
 */
interface SearchProvider {
    fun getDomain(): SearchDomain
    suspend fun search(query: String): List<SearchResult>
}

/**
 * 单条搜索结果。
 *
 * @property entity 命中的可搜索实体
 * @property score 匹配度 0.0 - 1.0；1.0 = 完全匹配 title，0.5 = 关键词命中
 * @property matchedField 命中的字段名（"title" / "subtitle" / "keywords" / "content" 等），用于调试
 * @property matchedSnippet 命中片段（已截断到 ~80 字符），UI 层基于 query 做高亮
 */
data class SearchResult(
    val entity: SearchableEntity,
    val score: Double,
    val matchedField: String,
    val matchedSnippet: String
)

/* ==================== 评分工具 ==================== */

/**
 * 简单的匹配度计算工具。各 Provider 调用本工具避免重复实现。
 *
 * 评分规则：
 * - title 完全相等：1.0
 * - title 以 query 开头：0.9
 * - title 包含 query：0.8
 * - subtitle 包含 query：0.5
 * - keywords 任一包含 query：0.6
 * - keywords 任一以 query 开头：0.7
 * - 正文（content）包含 query：0.4
 * - 否则不命中（返回 null）
 *
 * @return Triple(score, matchedField, matchedSnippet)；不命中返回 null
 */
internal fun scoreMatch(
    query: String,
    title: String,
    subtitle: String,
    keywords: List<String>,
    content: String? = null
): Triple<Double, String, String>? {
    val t = title.lowercase()
    val s = subtitle.lowercase()
    val q = query

    return when {
        t == q -> Triple(1.0, "title", snippet(title, q))
        t.startsWith(q) -> Triple(0.9, "title", snippet(title, q))
        t.contains(q) -> Triple(0.8, "title", snippet(title, q))
        keywords.any { it.lowercase().startsWith(q) } -> {
            val kw = keywords.first { it.lowercase().startsWith(q) }
            Triple(0.7, "keywords", kw)
        }
        keywords.any { it.lowercase().contains(q) } -> {
            val kw = keywords.first { it.lowercase().contains(q) }
            Triple(0.6, "keywords", kw)
        }
        s.contains(q) -> Triple(0.5, "subtitle", snippet(subtitle, q))
        content != null && content.lowercase().contains(q) ->
            Triple(0.4, "content", snippet(content, q))
        else -> null
    }
}

/** 取 query 周围 ~80 字符上下文。 */
private fun snippet(text: String, query: String): String {
    val lower = text.lowercase()
    val idx = lower.indexOf(query.lowercase())
    if (idx < 0) return text.take(80)
    val start = (idx - 30).coerceAtLeast(0)
    val end = (idx + query.length + 50).coerceAtMost(text.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + text.substring(start, end) + suffix
}
