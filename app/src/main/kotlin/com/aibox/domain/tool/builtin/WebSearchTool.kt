package com.aibox.domain.tool.builtin

import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.tool.ParamSpec
import com.aibox.domain.tool.ParamType
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.domain.tool.ToolContext
import com.aibox.domain.tool.ToolParameterSchema
import com.aibox.domain.tool.ToolResult
import com.aibox.util.log.LogSystem
import com.aibox.util.outcome.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * WebSearchTool（B3 基础工具）。
 *
 * 联网搜索工具，支持 3 个搜索引擎：
 * - **Tavily**：商业 LLM 优化搜索 API（需 API key，用户在 Settings 配置）
 * - **Exa**：神经搜索 API（需 API key，用户在 Settings 配置）
 * - **DuckDuckGo**（默认）：HTML 抓取，无需 API key
 *
 * - 工具 ID：`web_search`（默认启用）
 * - 分类：[ToolCategory.BASIC]，自动执行（联网搜索无副作用，无需用户确认）
 *
 * 设计原则：
 * - 零异常约定：所有路径返回 [Outcome]，HTTP / 解析失败转 [Outcome.Failure]
 * - IO 走 [Dispatchers.IO]（OkHttp 同步请求 + Jsoup 解析都不阻塞主线程）
 * - 超时 10s（connect / read 各 10s），匹配任务约束
 * - 默认引擎 DuckDuckGo 不需要 API key（保证零配置开箱即用）
 *
 * 输出：
 * - [ToolResult.output]：多行可读文本，含查询词、结果数、每条结果的 标题 + URL + 摘要
 * - [ToolResult.structuredData]：[List]<[SearchResultItem]>，UI 直接渲染卡片
 *
 * 失败场景：
 * - query 为空 → [Outcome.Failure] ("Missing query")
 * - max_results 非法（≤0 或 >20）→ 截断到 [1, 20]
 * - 网络错误 / HTTP 4xx/5xx / 解析错误 → [Outcome.Failure]（含原因）
 * - Tavily/Exa 引擎但 API key 未配置 → [Outcome.Failure] ("API key not configured")
 *
 * 关于 DuckDuckGo HTML 抓取的稳定性说明：
 * - DDG HTML 端点 `https://html.duckduckgo.com/html/` 通过 POST 表单 q=QUERY 提交
 * - 用真实浏览器 User-Agent 避免被拒；返回 HTML 用 Jsoup 解析 `.result` / `.result__a` / `.result__snippet`
 * - DDG 可能随时调整 HTML 结构；若解析失败返回空列表 + 警告日志（不阻塞 LLM 对话）
 */
class WebSearchTool(
    private val settingsRepository: SettingsRepository
) : Tool {
    override val id: String = "web_search"
    override val displayName: String = "联网搜索"
    override val description: String =
        "搜索互联网获取实时信息。支持中英文。返回多个结果含标题、URL、摘要。"
    override val category: ToolCategory = ToolCategory.BASIC
    override val icon: String = "🔍"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = listOf(
            ParamSpec(
                name = "query",
                type = ParamType.STRING,
                description = "搜索关键词"
            )
        ),
        optional = listOf(
            ParamSpec(
                name = "max_results",
                type = ParamType.INTEGER,
                description = "返回结果数,默认 5,最大 20",
                default = 5
            ),
            ParamSpec(
                name = "search_engine",
                type = ParamType.STRING,
                description = "搜索引擎: tavily / exa / duckduckgo",
                enum = listOf("tavily", "exa", "duckduckgo"),
                default = "duckduckgo"
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        val query = (params["query"] as? String)?.takeIf { it.isNotBlank() }
            ?: return Outcome.Failure("Missing query")

        // max_results：解析 + 截断到 [1, 20]；兼容 LLM 传 Number / Int / String
        val maxResultsRaw = (params["max_results"] as? Number)?.toInt() ?: 5
        val maxResults = maxResultsRaw.coerceIn(1, 20)

        val engine = (params["search_engine"] as? String)?.takeIf { it.isNotBlank() } ?: "duckduckgo"

        return try {
            LogSystem.info(TAG, "WebSearch: query='$query', engine=$engine, max=$maxResults")

            val results = when (engine) {
                "tavily" -> searchWithTavily(query, maxResults)
                "exa" -> searchWithExa(query, maxResults)
                "duckduckgo" -> searchWithDuckDuckGo(query, maxResults)
                else -> {
                    LogSystem.warn(TAG, "Unknown engine '$engine', fallback to duckduckgo")
                    searchWithDuckDuckGo(query, maxResults)
                }
            }

            val output = buildString {
                appendLine("搜索 \"$query\" 找到 ${results.size} 条结果（引擎: $engine）:")
                appendLine()
                if (results.isEmpty()) {
                    appendLine("(无结果)")
                } else {
                    results.forEachIndexed { i, r ->
                        appendLine("${i + 1}. ${r.title}")
                        appendLine("   URL: ${r.url}")
                        appendLine("   ${r.snippet}")
                        appendLine()
                    }
                }
            }

            LogSystem.info(TAG, "WebSearch done: ${results.size} results")

            Outcome.Success(
                ToolResult(
                    success = true,
                    output = output,
                    structuredData = results,
                    executionTimeMs = 0
                )
            )
        } catch (e: Throwable) {
            LogSystem.error(TAG, "WebSearch error: ${e.message}", e)
            Outcome.Failure("WebSearchTool error: ${e.message}", e)
        }
    }

    // ===================================================
    // DuckDuckGo（默认，无需 API key）
    // ===================================================

    /**
     * 用 DuckDuckGo HTML 端点搜索。
     *
     * 端点：`https://html.duckduckgo.com/html/`，POST 表单 `q=QUERY`
     * 解析：Jsoup 选 `.result` / `.result__a`（链接 + 标题） / `.result__snippet`（摘要）
     */
    private suspend fun searchWithDuckDuckGo(query: String, max: Int): List<SearchResultItem> =
        withContext(Dispatchers.IO) {
            val client = buildClient()
            val formBody = "q=" + java.net.URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .post(formBody.toRequestBody(FORM_MEDIA_TYPE))
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogSystem.warn(TAG, "DDG HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val html = resp.body?.string() ?: return@withContext emptyList()
                val doc = Jsoup.parse(html)
                val items = mutableListOf<SearchResultItem>()
                doc.select(".result").forEach { el ->
                    if (items.size >= max) return@forEach
                    val link = el.selectFirst(".result__a") ?: return@forEach
                    val title = link.text().trim()
                    val href = link.attr("abs:href")
                    val snippet = el.selectFirst(".result__snippet")?.text()?.trim() ?: ""
                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        items.add(SearchResultItem(title = title, url = href, snippet = snippet))
                    }
                }
                items
            }
        }

    // ===================================================
    // Tavily（需 API key）
    // ===================================================

    /**
     * 用 Tavily API 搜索。POST `https://api.tavily.com/search` JSON:
     * ```json
     * { "api_key": "...", "query": "...", "max_results": 5 }
     * ```
     * 响应 results 数组每项含 title / url / content。
     */
    private suspend fun searchWithTavily(query: String, max: Int): List<SearchResultItem> =
        withContext(Dispatchers.IO) {
            val apiKey = settingsRepository.tavilyApiKey.first()
            if (apiKey.isBlank()) {
                throw IllegalStateException("Tavily API key not configured (Settings → 工具 → Tavily API Key)")
            }

            val client = buildClient()
            val body = JSONObject().apply {
                put("api_key", apiKey)
                put("query", query)
                put("max_results", max)
                put("search_depth", "basic")
                put("include_answer", false)
            }.toString()

            val req = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Tavily HTTP ${resp.code}")
                }
                val text = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(text)
                val arr: JSONArray = json.optJSONArray("results") ?: return@withContext emptyList()
                val items = mutableListOf<SearchResultItem>()
                for (i in 0 until arr.length()) {
                    if (items.size >= max) break
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    val url = obj.optString("url", "").trim()
                    val content = obj.optString("content", "").trim()
                    if (title.isNotEmpty() && url.isNotEmpty()) {
                        items.add(SearchResultItem(title, url, content))
                    }
                }
                items
            }
        }

    // ===================================================
    // Exa（需 API key）
    // ===================================================

    /**
     * 用 Exa API 搜索。POST `https://api.exa.ai/search` JSON:
     * ```json
     * { "query": "...", "numResults": 5, "contents": { "text": { "maxCharacters": 200 } } }
     * ```
     * 头部：`x-api-key: ...`
     * 响应 results 数组每项含 title / url / text。
     */
    private suspend fun searchWithExa(query: String, max: Int): List<SearchResultItem> =
        withContext(Dispatchers.IO) {
            val apiKey = settingsRepository.exaApiKey.first()
            if (apiKey.isBlank()) {
                throw IllegalStateException("Exa API key not configured (Settings → 工具 → Exa API Key)")
            }

            val client = buildClient()
            val contents = JSONObject().apply {
                put("text", JSONObject().apply { put("maxCharacters", 200) })
            }
            val body = JSONObject().apply {
                put("query", query)
                put("numResults", max)
                put("contents", contents)
            }.toString()

            val req = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Exa HTTP ${resp.code}")
                }
                val text = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(text)
                val arr: JSONArray = json.optJSONArray("results") ?: return@withContext emptyList()
                val items = mutableListOf<SearchResultItem>()
                for (i in 0 until arr.length()) {
                    if (items.size >= max) break
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    val url = obj.optString("url", "").trim()
                    // Exa API：contents.text=true 时每个 result 含 text 字符串（不是嵌套对象）
                    val snippet = obj.optString("text", "").trim()
                    if (title.isNotEmpty() && url.isNotEmpty()) {
                        items.add(SearchResultItem(title, url, snippet))
                    }
                }
                items
            }
        }

    /** 共享 OkHttp 客户端，10s connect / 10s read 超时（任务约束：URL 阅读器超时 10s 同源）。 */
    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "aibox.tool.web_search"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 单个搜索结果项（WebSearchTool 输出 + UrlReaderTool 链接列表复用）。
 *
 * @param title 结果标题（HTML 解析后纯文本）
 * @param url 结果 URL（绝对路径）
 * @param snippet 摘要文本（DDG: snippet / Tavily: content / Exa: text）
 */
data class SearchResultItem(
    val title: String,
    val url: String,
    val snippet: String
)
