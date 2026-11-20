package com.aibox.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
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
    lineHeight: Int = 24,
    /** 点击 `aibox:choices` 组件按钮时回调，一般用于直接把选项发给模型 */
    onChoice: (String) -> Unit = {},
    /**
     * 历史消息里的选项是否还可点。
     *
     * 只有最新一条助手消息需要可点：旧选项继续做成按钮会让整屏都是菜单，
     * 也容易误点到很早以前的选项。
     */
    choicesEnabled: Boolean = true,
    /** 是否仍在流式输出。流式中的未闭合 mermaid 围栏先按代码块显示，结束后再兜底渲染成图。 */
    streaming: Boolean = false,
    /** 流式时在文本末尾渲染呼吸光标；减弱动态效果时传 false。 */
    showCaret: Boolean = false
) {
    // B：流式期间轻量渲染——打字机每 20ms 推进一次，若每次都跑整段 parseBlocks + parseInline
    // （逐块正则、建 AnnotatedString），长文本后段会明显掉帧、发热。流式时直接纯文本直出，
    // 等生成结束（streaming=false）再切回完整 Markdown 解析。
    if (streaming) {
        // 呼吸光标直接拼在文字尾部：整段是一个 Text，光标随文字一起折行，
        // 永远落在“最后一个字”之后。之前用 Row + Text(weight(1f,fill=true)) + 独立光标，
        // weight 把文本撑满整行，光标被顶到“行尾”，看起来像在输入框末尾而不是文字末尾。
        val shown: AnnotatedString = if (showCaret) {
            val caretAlpha = rememberCaretAlpha()
            buildAnnotatedString {
                append(text)
                withStyle(SpanStyle(color = color.copy(alpha = caretAlpha))) { append("▌") }
            }
        } else {
            AnnotatedString(text)
        }
        Text(
            text = shown,
            color = color,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = baseFontSize.sp,
                lineHeight = lineHeight.sp
            ),
            modifier = modifier.fillMaxWidth()
        )
        return
    }
    val blocks = remember(text, choicesEnabled) { parseBlocks(text, choicesEnabled, false) }
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

                is MdBlock.Mermaid -> {
                    val isMindmap = block.code.trimStart()
                        .startsWith("mindmap", ignoreCase = true)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = codeBg,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            if (isMindmap) {
                                // 思维导图：自研 Canvas 渲染，离线、无 WebView 竞态、无外链脚本。
                                MindMapCanvas(block.code)
                            } else {
                                Text(
                                    "mermaid",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                                )
                                MermaidBlock(block.code)
                            }
                        }
                    }
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

                is MdBlock.Math -> Text(
                    latexToAnnotated(block.tex),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp),
                    softWrap = false,
                    textAlign = if (block.display) TextAlign.Center else TextAlign.Start,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = (baseFontSize + 2).sp,
                        lineHeight = (lineHeight + 8).sp,
                        fontFamily = FontFamily.Serif
                    )
                )

                is MdBlock.Image -> MdImage(block)

                is MdBlock.Task -> Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        if (block.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                        contentDescription = if (block.checked) "已完成" else "未完成",
                        tint = if (block.checked) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        parseInline(block.text, primary),
                        color = if (block.checked) MaterialTheme.colorScheme.onSurfaceVariant else color,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = baseFontSize.sp,
                            lineHeight = lineHeight.sp,
                            textDecoration = if (block.checked) TextDecoration.LineThrough else null
                        )
                    )
                }

                is MdBlock.Choices -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.options.forEachIndexed { idx, option ->
                        if (!choicesEnabled) {
                            // 历史选项：降级成纯文本，避免整屏都是可点菜单
                            Text(
                                "${'A' + idx}. $option",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            OutlinedButton(
                                onClick = { onChoice(option) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${'A' + idx}. $option",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 呼吸光标的透明度：█ 在 1f↔0.15f 之间缓慢明灭，表明“还在写”。 */
@Composable
private fun rememberCaretAlpha(): Float {
    val alpha by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caretAlpha"
    )
    return alpha
}

/* ---------------- 块级解析 ---------------- */

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val marker: String, val ordered: Boolean, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    /** Mermaid 图（```mermaid），内置渲染，非 aibox 扩展组件。 */
    data class Mermaid(val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Math(val tex: String, val display: Boolean) : MdBlock
    data class Image(val alt: String, val url: String) : MdBlock
    data class Task(val checked: Boolean, val text: String) : MdBlock
    /** `aibox:choices` 组件：把若干选项渲染成按钮，点击即发送 */
    data class Choices(val options: List<String>) : MdBlock
    data object Divider : MdBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val ulRegex = Regex("^[-*+]\\s+(.*)$")
private val taskRegex = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.*)$")
private val olRegex = Regex("^(\\d+)[.)]\\s+(.*)$")
private val hrRegex = Regex("^\\s*([-*_])\\1{2,}\\s*$")
private val imageRegex = Regex("^!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)$")

private fun parseBlocks(src: String, choicesEnabled: Boolean = true, streaming: Boolean = false): List<MdBlock> {
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

        // 独立成行的图片
        val img = imageRegex.matchEntire(trimmed)
        if (img != null) {
            flushParagraph(); flushQuote()
            blocks.add(MdBlock.Image(img.groupValues[1], img.groupValues[2]))
            i++
            continue
        }

        // $$ 独立公式块
        if (trimmed.startsWith("$$")) {
            flushParagraph(); flushQuote()
            val rest = trimmed.removePrefix("$$").removeSuffix("$$").trim()
            if (rest.isNotEmpty() && trimmed.endsWith("$$") && trimmed.length > 4) {
                blocks.add(MdBlock.Math(rest, true))
                i++
            } else {
                val tex = StringBuilder(rest)
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("$$")) {
                    if (tex.isNotEmpty()) tex.append('\n')
                    tex.append(lines[i].trim())
                    i++
                }
                if (i < lines.size) i++
                if (tex.isNotBlank()) blocks.add(MdBlock.Math(tex.toString(), true))
            }
            continue
        }

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
            val closed = i < lines.size // 是否已遇到结束围栏
            if (closed) i++ // 跳过结束围栏
            // 组件扩展：```aibox:choices
            if (choicesEnabled && (lang == "choices" || lang == "aibox:choices")) {
                val options = code.toString().lineSequence()
                    .map {
                        it.trim()
                            .removePrefix("- ").removePrefix("* ").trim()
                            // 去掉 "A. " / "1、" 这类序号，按钮上只留用户口吻的正文
                            .replace(Regex("^[A-Za-zＡ-Ｚ][.、:：)）]\\s*"), "")
                            .replace(Regex("^\\d+[.、:：)）]\\s*"), "")
                            .trim()
                    }
                    .filter { it.isNotEmpty() }
                    .take(4) // 选项过多会把整屏占满，也会诱导模型每轮都出菜单
                    .toList()
                if (options.isNotEmpty()) blocks.add(MdBlock.Choices(options))
            } else if (lang.equals("mermaid", ignoreCase = true) && (closed || !streaming)) {
                // Mermaid 图：内置渲染，不依赖 choicesEnabled 扩展开关。
                // 流式未写完时按代码块显示，避免 WebView 抖动；流式结束后即便围栏因截断未闭合
                // 也兜底渲染成图，否则被截断的图会永远显示成源码。
                blocks.add(MdBlock.Mermaid(code.toString().trimEnd()))
            } else {
                blocks.add(MdBlock.Code(lang, code.toString().trimEnd()))
            }
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

            taskRegex.matches(trimmed) -> {
                flushParagraph(); flushQuote()
                val m = taskRegex.find(trimmed)!!
                blocks.add(MdBlock.Task(m.groupValues[1].isNotBlank(), m.groupValues[2]))
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

    // 列数较多时保留横向滚动兜底，常见 2~4 列按可用宽度均分、自动换行，避免撑破屏幕。
    val scrollable = cols > 4
    val containerFill = if (scrollable) Modifier else Modifier.fillMaxWidth()
    // weight 是 RowScope 扩展，必须在 Row 的 content lambda 内调用，不能在外部构造 Modifier。

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        color = rowBg,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(containerFill) {
            Row(
                containerFill.background(headerBg)
            ) {
                for (c in 0 until cols) {
                    val cellMod = if (scrollable) {
                        Modifier.widthIn(min = 72.dp, max = 200.dp)
                    } else {
                        Modifier.weight(1f)
                    }
                    Text(
                        parseInline(block.header.getOrElse(c) { "" }, primary),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = cellMod.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
            block.rows.forEachIndexed { idx, row ->
                if (idx > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Row(containerFill) {
                    for (c in 0 until cols) {
                        val cellMod = if (scrollable) {
                            Modifier.widthIn(min = 72.dp, max = 200.dp)
                        } else {
                            Modifier.weight(1f)
                        }
                        Text(
                            parseInline(row.getOrElse(c) { "" }, primary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = cellMod.padding(horizontal = 8.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- 思考内容折叠 ---------------- */

/**
 * 思考过程折叠块。
 *
 * 按需求「不用框」：不放 Surface 背景，只留一行淡淡的标题（图标 + 字数），
 * 默认收起，点一下展开正文；流式生成中显示「正在思考…」。
 */
@Composable
fun ThinkingSection(thinking: String, streaming: Boolean) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (thinking.isBlank()) return
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = dim.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (streaming) "正在思考…" else "思维过程 · ${thinking.length} 字",
                style = MaterialTheme.typography.labelMedium,
                color = dim.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(16.dp),
                tint = dim.copy(alpha = 0.6f)
            )
        }
        if (expanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(IntrinsicSize.Min)
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(dim.copy(alpha = 0.25f))
                )
                Spacer(Modifier.width(10.dp))
                // 思考正文也走 MarkdownText：标题/列表/加粗/行内代码/围栏代码块等都能渲染。
                // 复用 streaming 轻量路径（生成中纯文本直出、结束再全解析），避免逐 token
                // 重解析长思考内容；字号比正文略小，沿用 dim 色与淡竖线。
                MarkdownText(
                    text = thinking,
                    color = dim,
                    baseFontSize = 13,
                    lineHeight = 19,
                    streaming = streaming,
                    choicesEnabled = false,
                    showCaret = false
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

    // CommonMark 规则：'_' 作为强调标记不能出现在单词内部
    // （两侧均为字母/数字时视为普通字符），'*' 无此限制。
    // 否则 q8_0 / q4_0 这类量化名会被误配对成斜体、吞掉下划线。
    fun isWordChar(c: Char) = c.isLetterOrDigit()

    fun canOpen(marker: String, pos: Int): Boolean {
        if (marker[0] == '*') return true
        return !(pos > 0 && isWordChar(src[pos - 1]))
    }

    fun canClose(marker: String, pos: Int): Boolean {
        if (marker[0] == '*') return true
        val after = pos + marker.length
        return !(after < src.length && isWordChar(src[after]))
    }

    fun findClosing(marker: String, from: Int): Int {
        var p = from
        while (p < src.length) {
            val found = src.indexOf(marker, p)
            if (found < 0) return -1
            if (canClose(marker, found)) return found
            p = found + marker.length
        }
        return -1
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
                val end = if (canOpen(marker, i)) findClosing(marker, i + 2) else -1
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
            // 行内公式 $...$
            src[i] == '$' && i + 1 < src.length && src[i + 1] != '$' -> {
                val end = src.indexOf('$', i + 1)
                if (end > i + 1 && !src.substring(i + 1, end).contains('\n')) {
                    flush()
                    append(latexToAnnotated(src.substring(i + 1, end)))
                    i = end + 1
                } else { buffer.append('$'); i++ }
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
                val marker = src[i].toString()
                val end = if (canOpen(marker, i)) findClosing(marker, i + 1) else -1
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

/* ---------------- 轻量 LaTeX ---------------- */

private val latexSymbols = linkedMapOf(
    "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
    "\\epsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
    "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
    "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ",
    "\\sigma" to "σ", "\\tau" to "τ", "\\phi" to "φ", "\\chi" to "χ",
    "\\psi" to "ψ", "\\omega" to "ω",
    "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
    "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ",
    "\\Psi" to "Ψ", "\\Omega" to "Ω",
    "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±",
    "\\mp" to "∓", "\\le" to "≤", "\\leq" to "≤", "\\ge" to "≥", "\\geq" to "≥",
    "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
    "\\infty" to "∞", "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫",
    "\\partial" to "∂", "\\nabla" to "∇", "\\sqrt" to "√",
    "\\to" to "→", "\\rightarrow" to "→", "\\Rightarrow" to "⇒",
    "\\leftarrow" to "←", "\\Leftarrow" to "⇐", "\\iff" to "⇔",
    "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\supset" to "⊃",
    "\\subseteq" to "⊆", "\\supseteq" to "⊇", "\\emptyset" to "∅",
    "\\cup" to "∪", "\\cap" to "∩", "\\forall" to "∀", "\\exists" to "∃",
    "\\angle" to "∠", "\\perp" to "⊥", "\\parallel" to "∥",
    "\\langle" to "⟨", "\\rangle" to "⟩",
    "\\cdots" to "⋯", "\\ldots" to "…", "\\propto" to "∝",
    "\\sim" to "∼", "\\cong" to "≅", "\\therefore" to "∴", "\\because" to "∵",
    "\\ell" to "ℓ", "\\prime" to "′",
    "\\degree" to "°", "\\circ" to "∘",
)

/**
 * 把常见 LaTeX 片段转成可读的 Unicode / 上下标。
 *
 * 不做完整数学排版：目标是流式输出里能看懂，而不是替代 KaTeX。
 * 遇到 `\begin{...}` 这类复杂环境时原样返回，避免渲染出错。
 */
private fun latexToAnnotated(tex: String): AnnotatedString {
    var s = tex.trim()
    if (s.contains("\\begin")) return AnnotatedString(s)

    // 只去掉 \left / \right 命令本身，保留其后的括号；不能直接 replace("\\left")
    // 否则 \leftarrow 会被误伤成 "arrow"
    s = s.replace(Regex("""\\(left|right)(?![a-zA-Z])"""), "")
    s = s.replace("\\,", " ").replace("\\;", " ").replace("\\!", "")
        .replace("\\quad", "  ").replace("\\qquad", "    ")
        .replace("\\\\", "\n")
    s = Regex("""\\text\{([^{}]*)\}""").replace(s) { it.groupValues[1] }
    s = Regex("""\\mathrm\{([^{}]*)\}""").replace(s) { it.groupValues[1] }
    // 嵌套分式按由内向外替换三轮
    repeat(3) {
        s = Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""").replace(s) { m ->
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            val an = if (a.length <= 1) a else "($a)"
            val bn = if (b.length <= 1) b else "($b)"
            "$an⁄$bn"
        }
    }
    s = Regex("""\\sqrt\{([^{}]*)\}""").replace(s) { "√(${it.groupValues[1]})" }
    // 长命令优先替换，避免 \le 先吃掉 \leq、\in 先吃掉 \infty / \int
    latexSymbols.entries.sortedByDescending { it.key.length }
        .forEach { (cmd, sym) -> s = s.replace(cmd, sym) }
    // 未识别的命令去掉反斜杠保留名字
    s = Regex("""\\[a-zA-Z]+""").replace(s) { it.value.drop(1) }

    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '^' || c == '_') {
                val superScript = c == '^'
                var j = i + 1
                var content = ""
                if (j < s.length && s[j] == '{') {
                    val e = s.indexOf('}', j)
                    if (e > j) {
                        content = s.substring(j + 1, e)
                        j = e + 1
                    } else {
                        j++
                    }
                } else if (j < s.length) {
                    content = s[j].toString()
                    j++
                }
                val shift = if (superScript) BaselineShift.Superscript else BaselineShift.Subscript
                withStyle(SpanStyle(baselineShift = shift, fontSize = 12.sp)) { append(content) }
                i = j
            } else {
                append(c)
                i++
            }
        }
    }
}

/* ---------------- 图片 ---------------- */

/** 直接解码远程 / 本地图片，避免为 Markdown 引入额外的图片库依赖。 */
@Composable
private fun MdImage(block: MdBlock.Image) {
    val ctx = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, block.url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val input = when {
                    block.url.startsWith("http://") || block.url.startsWith("https://") ->
                        java.net.URL(block.url).openStream()
                    block.url.startsWith("file://") ->
                        java.io.File(java.net.URI(block.url)).inputStream()
                    else -> ctx.contentResolver.openInputStream(android.net.Uri.parse(block.url))
                }
                input?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    val image = bmp
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = block.alt,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Text(
            "图片：${block.alt.ifBlank { block.url }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
