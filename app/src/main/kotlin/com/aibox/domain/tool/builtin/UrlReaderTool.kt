package com.aibox.domain.tool.builtin

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
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * UrlReaderTool（B4 基础工具）。
 *
 * 抓取指定 URL 的网页内容，返回纯文本（HTML 已剥离）。支持指定最大长度 + 是否包含链接列表。
 *
 * - 工具 ID：`url_reader`（默认启用）
 * - 分类：[ToolCategory.BASIC]，自动执行（只读抓取，无副作用）
 *
 * 实现选型：
 * - 用 [Jsoup.connect] 直接抓取 + 解析（Jsoup 内置 HTTP 客户端，避免重复引入 OkHttp；B3 已引入 OkHttp 但 URL 阅读单次无需复杂 header 控制）
 * - 抓取超时 10s（与 [WebSearchTool] 同约束）
 * - 最大返回 5000 字符（任务约束）
 *
 * 设计原则：
 * - 零异常约定：网络 / 解析失败转 [Outcome.Failure]，含原因
 * - IO 走 [Dispatchers.IO]（Jsoup 同步 connect 不阻塞主线程）
 * - 不抓取需要 JS 渲染的 SPA 站点（Jsoup 只解析静态 HTML；若 LLM 想要 SPA 内容应换 WebSearchTool）
 * - 不下载图片 / 视频 / 二进制（仅 text/html + application/xhtml+xml）
 *
 * 输出：
 * - [ToolResult.output]：多行文本，含 标题 + URL + 正文（按 max_length 截断）+ 相关链接（可选）
 * - [ToolResult.structuredData]：Map<String, Any>，含 `title` / `url` / `text` / `links`
 *
 * 失败场景：
 * - url 为空 → [Outcome.Failure] ("Missing url")
 * - url 不是合法 http(s) → [Outcome.Failure]（MalformedURLException / IllegalArgumentException）
 * - 网络错误 / HTTP 4xx/5xx → [Outcome.Failure]（IOException / HttpStatusException）
 *
 * 关于链接列表的说明：
 * - `include_links=true` 时抓取 `<a href>` 前 10 个，给 LLM 提供进一步探索的线索
 * - 链接文本可能为空（如 `<a href="..."><img></a>`），保留但不输出空 text
 */
class UrlReaderTool : Tool {
    override val id: String = "url_reader"
    override val displayName: String = "URL 阅读器"
    override val description: String =
        "抓取指定 URL 的网页内容,返回纯文本(去除 HTML)。支持指定最大长度。"
    override val category: ToolCategory = ToolCategory.BASIC
    override val icon: String = "🌐"

    override fun getParameterSchema(): ToolParameterSchema = ToolParameterSchema(
        required = listOf(
            ParamSpec(
                name = "url",
                type = ParamType.STRING,
                description = "要读取的 URL（http/https）"
            )
        ),
        optional = listOf(
            ParamSpec(
                name = "max_length",
                type = ParamType.INTEGER,
                description = "返回文本最大长度,默认 5000",
                default = 5000
            ),
            ParamSpec(
                name = "include_links",
                type = ParamType.BOOLEAN,
                description = "是否包含链接列表,默认 true",
                default = true
            )
        )
    )

    override suspend fun execute(
        params: Map<String, Any>,
        context: ToolContext
    ): Outcome<ToolResult> {
        val url = (params["url"] as? String)?.takeIf { it.isNotBlank() }
            ?: return Outcome.Failure("Missing url")

        val maxLength = (params["max_length"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 5000
        val includeLinks = (params["include_links"] as? Boolean) ?: true

        return try {
            LogSystem.info(TAG, "UrlReader: url=$url, max=$maxLength, includeLinks=$includeLinks")

            val doc = withContext(Dispatchers.IO) {
                Jsoup.connect(url)
                    .userAgent(BROWSER_UA)
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(MAX_BODY_BYTES)
                    .followRedirects(true)
                    .ignoreContentType(false)   // 拒绝 image / video / 二进制
                    .get()
            }

            val title = doc.title().trim()
            val text = doc.body()?.text()?.take(maxLength) ?: ""
            val links = if (includeLinks) {
                extractLinks(doc, MAX_LINKS)
            } else {
                emptyList()
            }

            val output = buildString {
                appendLine("标题: $title")
                appendLine("URL: $url")
                appendLine()
                if (text.isEmpty()) {
                    appendLine("(无正文内容，可能是 SPA 或非 HTML 资源)")
                } else {
                    appendLine(text)
                }
                if (includeLinks && links.isNotEmpty()) {
                    appendLine()
                    appendLine("相关链接:")
                    links.forEach { (linkUrl, linkText) ->
                        if (linkText.isBlank()) {
                            appendLine("- $linkUrl")
                        } else {
                            appendLine("- $linkText: $linkUrl")
                        }
                    }
                }
            }

            LogSystem.info(TAG, "UrlReader done: title='$title', textLen=${text.length}, links=${links.size}")

            Outcome.Success(
                ToolResult(
                    success = true,
                    output = output,
                    structuredData = mapOf<String, Any>(
                        "title" to title,
                        "url" to url,
                        "text" to text,
                        "links" to links
                    ),
                    executionTimeMs = 0
                )
            )
        } catch (e: Throwable) {
            LogSystem.error(TAG, "UrlReader error: ${e.message}", e)
            Outcome.Failure("UrlReaderTool error: ${e.message}", e)
        }
    }

    /** 提取前 [max] 个绝对路径链接（过滤页内锚 # 和 javascript:）。 */
    private fun extractLinks(doc: Document, max: Int): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        doc.select("a[href]").forEach { el ->
            if (result.size >= max) return@forEach
            val href = el.attr("abs:href")
            if (href.isBlank()) return@forEach
            if (href.startsWith("#")) return@forEach
            if (href.startsWith("javascript:")) return@forEach
            if (href.startsWith("mailto:")) return@forEach
            val text = el.text().trim()
            result.add(href to text)
        }
        return result
    }

    companion object {
        private const val TAG = "aibox.tool.url_reader"
        private const val TIMEOUT_MS = 10_000
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024   // 5MB HTTP body 上限，避免抓大文件
        private const val MAX_LINKS = 10
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
