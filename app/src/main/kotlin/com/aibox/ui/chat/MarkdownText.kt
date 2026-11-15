package com.aibox.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染器（无第三方依赖）。
 *
 * 支持：
 * - 标题 `#` ~ `######`
 * - 加粗 `**x**` / 斜体 `*x*` / 加粗斜体 `***x***` / 删除线 `~~x~~`
 * - 行内代码 `` `x` ``
 * - 围栏代码块 ```lang ... ```
 * - 引用 `> x`（多行）
 * - 无序列表 `- x` / `* x` / `+ x`，有序列表 `1. x`
 * - 分割线 `---` / `***`
 * - 链接 `[text](url)`，点击用系统浏览器打开
 * - `<u>下划线</u>`
 *
 * 面向流式输出：每次内容变化重新解析，块级结构不完整时按普通文本降级，不会崩溃。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    baseFontSize: Int = 16,
    lineHeight: Int = 24
) {
    val blocks = remember(text) { parseBlocks(text) }
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    parseInline(block.text, primary),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = when (block.level) {
                            1 -> 24.sp
                            2 -> 21.sp
                            3 -> 19.sp
                            else -> 17.sp
                        },
                        lineHeight = (baseFontSize + 9).sp,
                        fontWeight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold
                    )
                )

                is MdBlock.Paragraph -> Text(
                    parseInline(block.text, primary),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = baseFontSize.sp,
                        lineHeight = lineHeight.sp
                    )
                )

                is MdBlock.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(primary.copy(alpha = 0.6f))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        parseInline(block.text, primary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = (baseFontSize - 1).sp,
                            lineHeight = (lineHeight - 2).sp
                        )
                    )
                }

                is MdBlock.ListItem -> Row(Modifier.fillMaxWidth()) {
                    Text(
                        block.marker,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = baseFontSize.sp,
                            lineHeight = lineHeight.sp,
                            fontFamily = if (block.ordered) FontFamily.Monospace else FontFamily.Default
                        ),
                        modifier = Modifier.width(if (block.ordered) 30.dp else 20.dp)
                    )
                    Text(
                        parseInline(block.text, primary),
                        color = color,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = baseFontSize.sp,
                            lineHeight = lineHeight.sp
                        )
                    )
                }

                is MdBlock.Code -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = codeBg,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    block.lang.ifBlank { "code" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(block.code))
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.ContentCopy,
                                        contentDescription = "复制代码",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                block.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = color,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(end = 8.dp)
                            )
                        }
                    }
                }

                MdBlock.Divider -> HorizontalDivider(
                    Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                is MdBlock.Table -> MdTable(block)
            }
        }
    }
}

/* ---------------- 块级解析 ---------------- */

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val marker: String, val ordered: Boolean, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val ulRegex = Regex("^[-*+]\\s+(.*)$")
private val olRegex = Regex("^(\\d+)[.)]\\s+(.*)$")
private val hrRegex = Regex("^\\s*([-*_])\\1{2,}\\s*$")

private fun parseBlocks(src: String): List<MdBlock> {
    if (src.isBlank()) return emptyList()
    val lines = src.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    val quote = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }
    fun flushQuote() {
        if (quote.isNotBlank()) {
            blocks.add(MdBlock.Quote(quote.toString().trim()))
            quote.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val trimmed = line.trimStart()

        // 围栏代码块
        if (trimmed.startsWith("```")) {
            flushParagraph(); flushQuote()
            val lang = trimmed.removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++ // 跳过结束围栏
            blocks.add(MdBlock.Code(lang, code.toString().trimEnd()))
            continue
        }

        // Markdown 表格：当前行以 | 开头，且下一行是 |---|---| 分隔行
        if (trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            flushParagraph(); flushQuote()
            val header = splitTableRow(trimmed)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                rows.add(splitTableRow(lines[i].trimStart()))
                i++
            }
            blocks.add(MdBlock.Table(header, rows))
            continue
        }

        when {
            trimmed.isEmpty() -> { flushParagraph(); flushQuote() }

            hrRegex.matches(line) -> { flushParagraph(); flushQuote(); blocks.add(MdBlock.Divider) }

            headingRegex.matches(trimmed) -> {
                flushParagraph(); flushQuote()
                val m = headingRegex.find(trimmed)!!
                blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                quote.append(trimmed.removePrefix(">").trim()).append('\n')
            }

            ulRegex.matches(trimmed) -> {
                flushParagraph(); flushQuote()
                blocks.add(MdBlock.ListItem("•", false, ulRegex.find(trimmed)!!.groupValues[1]))
            }

            olRegex.matches(trimmed) -> {
                flushParagraph(); flushQuote()
                val m = olRegex.find(trimmed)!!
                blocks.add(MdBlock.ListItem("${m.groupValues[1]}.", true, m.groupValues[2]))
            }

            else -> {
                flushQuote()
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flushParagraph()
    flushQuote()
    return blocks
}

/* ---------------- 表格 ---------------- */

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|")) return false
    val cells = t.trim('|').split('|')
    return cells.isNotEmpty() && cells.all { it.trim().matches(Regex(":?-{2,}:?")) }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

@Composable
private fun MdTable(block: MdBlock.Table) {
    val primary = MaterialTheme.colorScheme.primary
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val rowBg = MaterialTheme.colorScheme.surfaceContainerLow
    val cols = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        color = rowBg,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            Row(Modifier.background(headerBg)) {
                for (c in 0 until cols) {
                    Text(
                        parseInline(block.header.getOrElse(c) { "" }, primary),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .widthIn(min = 76.dp, max = 220.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            block.rows.forEachIndexed { idx, row ->
                if (idx > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Row {
                    for (c in 0 until cols) {
                        Text(
                            parseInline(row.getOrElse(c) { "" }, primary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .widthIn(min = 76.dp, max = 220.dp)
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- 思考内容折叠 ---------------- */

/** 把思考标记从正文里切出来；两种标记都支持。 */
fun splitThinking(raw: String): Pair<String?, String> {
    val open = when {
        raw.startsWith("<!--think-->") -> "<!--think-->"
        raw.startsWith(" thinking") || raw.startsWith("Thinking Process:") -> null
        else -> return null to raw
    }
    if (open == null) {
        // 本地模型的原生思考块（Qwen3 输出  thinking…<｜end▁of▁thinking｜>）
        val begin = raw.indexOf(" thinking")
        if (begin < 0) return null to raw
        val end = raw.indexOf("<｜end▁of▁thinking｜>", begin)
        return if (end < 0) {
            raw.substring(begin + 7).trim() to ""
        } else {
            raw.substring(begin + 7, end).trim() to raw.substring(end + 8).trim()
        }
    }
    val end = raw.indexOf("<!--/think-->")
    return if (end < 0) {
        raw.removePrefix(open).trim() to ""
    } else {
        raw.substring(open.length, end).trim() to raw.substring(end + "<!--/think-->".length).trim()
    }
}

/**
 * 思考过程折叠块。默认收起，点一下展开；流式生成中显示「正在思考…」。
 */
@Composable
fun ThinkingSection(thinking: String, streaming: Boolean) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (thinking.isBlank()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (streaming) "正在思考…" else "已深度思考（${thinking.length} 字）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

/* ---------------- 行内解析 ---------------- */

private fun parseInline(src: String, primary: Color): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline)
    )
    val buffer = StringBuilder()

    fun flush() {
        if (buffer.isNotEmpty()) {
            append(buffer.toString())
            buffer.clear()
        }
    }

    var i = 0
    while (i < src.length) {
        when {
            // 加粗斜体
            src.startsWith("***", i) -> {
                val end = src.indexOf("***", i + 3)
                if (end > i + 3) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(src.substring(i + 3, end))
                    }
                    i = end + 3
                } else { buffer.append("***"); i += 3 }
            }
            // 加粗
            src.startsWith("**", i) || src.startsWith("__", i) -> {
                val marker = src.substring(i, i + 2)
                val end = src.indexOf(marker, i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(src.substring(i + 2, end))
                    }
                    i = end + 2
                } else { buffer.append(marker); i += 2 }
            }
            // 删除线
            src.startsWith("~~", i) -> {
                val end = src.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(src.substring(i + 2, end))
                    }
                    i = end + 2
                } else { buffer.append("~~"); i += 2 }
            }
            // 行内代码
            src[i] == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x22808080)
                        )
                    ) { append(src.substring(i + 1, end)) }
                    i = end + 1
                } else { buffer.append('`'); i++ }
            }
            // 斜体
            src[i] == '*' || src[i] == '_' -> {
                val marker = src[i]
                val end = src.indexOf(marker, i + 1)
                if (end > i + 1) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                } else { buffer.append(marker); i++ }
            }
            // 链接
            src[i] == '[' -> {
                val close = src.indexOf(']', i + 1)
                val openParen = if (close > 0) src.indexOf('(', close) else -1
                val closeParen = if (openParen > 0) src.indexOf(')', openParen) else -1
                if (close > i && openParen == close + 1 && closeParen > openParen) {
                    flush()
                    val label = src.substring(i + 1, close)
                    val url = src.substring(openParen + 1, closeParen).trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        pushLink(LinkAnnotation.Url(url, linkStyle))
                        append(label.ifBlank { url })
                        pop()
                    } else {
                        append(label)
                    }
                    i = closeParen + 1
                } else { buffer.append('['); i++ }
            }
            // 下划线标签
            src.startsWith("<u>", i) -> {
                val end = src.indexOf("</u>", i + 3)
                if (end > i + 3) {
                    flush()
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        append(src.substring(i + 3, end))
                    }
                    i = end + 4
                } else { buffer.append("<u>"); i += 3 }
            }
            else -> { buffer.append(src[i]); i++ }
        }
    }
    flush()
}
