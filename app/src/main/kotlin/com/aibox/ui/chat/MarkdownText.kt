package com.aibox.ui.chat

import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
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
    /** 点击就地释义词 `{{?术语|释义}}` 时回调：(术语, 释义)，用于就地展开释义卡 */
    onAskTerm: (String, String) -> Unit = { _, _ -> },
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
    showCaret: Boolean = false,
    /** 设置开启时对 ```html 围栏做即时 WebView 预览（默认关闭）。 */
    htmlPreview: Boolean = false
) {
    // 流式立即渲染 Markdown：token 直出 / 打字机每 20ms 推进，若每次都全量 parseBlocks 会掉帧。
    // 这里对流式文本做节流（最快 48ms 一次），并把 streaming 传给解析器：
    // 未闭合的围栏按代码块降级，闭合后才升级为图/表，避免半成品反复重建。
    val renderText = rememberStreamRenderText(text, streaming)
    val blocks = remember(renderText, choicesEnabled, streaming, htmlPreview) {
        parseBlocks(renderText, choicesEnabled, streaming, htmlPreview)
    }
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val clipboard = LocalClipboardManager.current
    // onAskTerm 每重组都是新 lambda，不能直接进 styledCache 的 key（会让缓存永远失效）。
    // 用 rememberUpdatedState 持有最新回调，再包一个稳定的函数引用传给 parseInline。
    val askTermState = rememberUpdatedState(onAskTerm)
    val stableAskTerm: (String, String) -> Unit = remember { { t, g -> askTermState.value(t, g) } }

    // 光标拼在最后一个文本型块末尾（静态 ▌，不再逐帧动画，避免为每条消息常驻动画）。
    val caretIndex = if (streaming && showCaret) {
        blocks.indexOfLast {
            it is MdBlock.Heading || it is MdBlock.Paragraph ||
                it is MdBlock.ListItem || it is MdBlock.Task || it is MdBlock.Quote
        }
    } else -1
    // parseInline 是逐字符构建 AnnotatedString，是流式期最重的一段。
    // 此前每次重组（滚动、其它 state 变化）都会对所有文本块重跑一遍；这里按
    // renderText 缓存，只在节流后的文本真正变化时重算一次，把重组开销压下来。
    val styledCache = remember(renderText, primary, color, caretIndex) {
        blocks.mapIndexed { index, block ->
            val t = when (block) {
                is MdBlock.Heading -> block.text
                is MdBlock.Paragraph -> block.text
                is MdBlock.Quote -> block.text
                is MdBlock.ListItem -> block.text
                is MdBlock.Task -> block.text
                else -> null
            }
            if (t == null) {
                null
            } else {
                val base = parseInline(t, primary, stableAskTerm)
                if (index == caretIndex) {
                    buildAnnotatedString {
                        append(base)
                        withStyle(SpanStyle(color = color.copy(alpha = 0.7f))) { append("▌") }
                    }
                } else base
            }
        }
    }
    fun styled(text: String, index: Int): AnnotatedString =
        styledCache.getOrNull(index) ?: parseInline(text, primary, stableAskTerm)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    styled(block.text, index),
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
                    styled(block.text, index),
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
                        styled(block.text, index),
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
                        styled(block.text, index),
                        color = color,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = baseFontSize.sp,
                            lineHeight = lineHeight.sp
                        )
                    )
                }

                is MdBlock.Mermaid -> {
                    // 流式期间一律按源码显示：即便围栏已闭合、后面仍在吐正文，也不实例化
                    // 导图 Canvas / WebView（反复重建既卡顿又放大崩溃风险）。生成结束
                    // （streaming=false）后才真正渲染成图。
                    if (streaming) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = codeBg,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(
                                    "mermaid",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                                )
                                Text(
                                    block.code,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = color,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                )
                            }
                        }
                    } else {
                        val isMindmap = block.code.trimStart()
                            .startsWith("mindmap", ignoreCase = true)
                        if (isMindmap) {
                            // 思维导图：自研 Canvas 渲染，离线、无 WebView 竞态、无外链脚本。
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = codeBg,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                MindMapCanvas(block.code)
                            }
                        } else {
                            // 通用 mermaid 引擎在真机实测不可用（引擎未加载），降级为
                            // 原生源码展示，不再实例化 WebView、不给红字报错。
                            MermaidFallback(block.code)
                        }
                    }
                }

                is MdBlock.Code -> when (block.lang.trim().lowercase()) {
                    "aibox:steps" -> StructSteps(block.code)
                    "aibox:compare" -> StructCompare(block.code)
                    "aibox:proscons" -> StructProsCons(block.code)
                    else -> Surface(
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

                is MdBlock.Html -> HtmlPreview(block.code)

                MdBlock.Divider -> HorizontalDivider(
                    Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                is MdBlock.Table -> MdTable(block, stableAskTerm)

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
                        styled(block.text, index),
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

/**
 * 流式渲染节流：云端 token 直出、打字机每 20ms 推进，若每次都全量解析 Markdown 会掉帧发热。
 * 这里把解析频率压到 ~48ms（约 3 帧）一次，减少"一顿一顿"的观感。
 *
 * 关键：LaunchedEffect 只以 streaming 为 key，流式期间 streaming 恒为 true、协程不会重启。
 * 旧实现用 snapshotFlow { text }，但 effect lambda 捕获的是"文本段首次出现那一刻"的 text 常量——
 * snapshotFlow 读不到任何 State 变化，只发射一次初始值就停了，shown 因此永远卡在首次快照
 * （如"我先获取"），直到 streaming=false 走 return text 才补全，表现为"中途吞半句"。
 * 改用 rememberUpdatedState 持有"始终指向最新 text"的 State，effect 每 48ms 采样一次，
 * 工具执行空窗期间没有新 token 也能保持最新值，彻底消除卡快照。
 *
 * 生成结束（streaming=false）停止采样，直接返回最终完整文本，保证末尾内容不丢。
 */
@Composable
private fun rememberStreamRenderText(text: String, streaming: Boolean): String {
    var shown by remember { mutableStateOf(text) }
    // 长生命周期协程里读的一定要是"活"的 State，不能是捕获的 text 常量。
    val latestText by rememberUpdatedState(text)
    LaunchedEffect(streaming) {
        if (!streaming) return@LaunchedEffect
        while (isActive) {
            delay(48)
            shown = latestText
        }
    }
    return if (streaming) shown else text
}

/* ---------------- 块级解析 ---------------- */

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val marker: String, val ordered: Boolean, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    /** HTML 围栏（```html），设置开启后在下方挂 WebView 预览。 */
    data class Html(val code: String) : MdBlock
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

private fun parseBlocks(src: String, choicesEnabled: Boolean = true, streaming: Boolean = false, htmlPreview: Boolean = false): List<MdBlock> {
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
            } else if (lang.equals("html", ignoreCase = true) && closed && htmlPreview) {
                // HTML 围栏：开关开启且围栏闭合才做预览；流式未闭合时仍按代码块显示。
                blocks.add(MdBlock.Html(code.toString().trimEnd()))
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
private fun MdTable(block: MdBlock.Table, onAskTerm: (String, String) -> Unit = { _, _ -> }) {
    val primary = MaterialTheme.colorScheme.primary
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val rowBg = MaterialTheme.colorScheme.surfaceContainerLow
    val cols = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    val density = LocalDensity.current

    // 表格横滑策略：按每列最长单元格估算理想宽度，总宽超过可用宽度才横滑；
    // 窄表按可用宽度均分、自动换行，不牺牲阅读体验；宽表/长内容则可横向滑动看更多列。
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availW = maxWidth
        val colWidths = (0 until cols).map { c ->
            val longest = maxOf(
                block.header.getOrElse(c) { "" }.length,
                block.rows.maxOfOrNull { it.getOrElse(c) { "" }.length } ?: 0
            )
            // CJK 大致按每字 13sp、ASCII 折半保守估算，再留左右 padding。
            with(density) { (longest * 8 + 24).sp.toDp() }.coerceIn(72.dp, 240.dp)
        }
        val totalIdeal = colWidths.fold(0.dp) { acc, w -> acc + w }
        val scrollable = totalIdeal > availW
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
                            Modifier.width(colWidths[c])
                        } else {
                            Modifier.weight(1f)
                        }
                        Text(
                            parseInline(block.header.getOrElse(c) { "" }, primary, onAskTerm),
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
                                Modifier.width(colWidths[c])
                            } else {
                                Modifier.weight(1f)
                            }
                            Text(
                                parseInline(row.getOrElse(c) { "" }, primary, onAskTerm),
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
}

/* ---------------- HTML 即时预览 ---------------- */

/**
 * ```html 围栏的即时预览。
 *
 * 安全：禁用 JavaScript、禁止文件访问、拦截所有外链（只在 WebView 内渲染内联 HTML），
 * 避免 XSS 与本地文件读取。限高 320dp，避免长页面把消息列表撑爆。
 * 只在 html 字符串真正变化时 loadData，避免重组导致每个 token 都 reload 闪烁。
 */
@Composable
private fun HtmlPreview(html: String) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val bg = if (isDark) "#1E1E1E" else "#FFFFFF"
    val fg = if (isDark) "#E6E6E6" else "#1A1A1A"
    // 包一层带主题底色的文档，保证预览在深/浅色下都可读。
    val document = remember(html, isDark) {
        "<!doctype html><meta charset='utf-8'>" +
            "<style>html,body{margin:0;padding:8px;background:$bg;color:$fg;" +
            "font-family:sans-serif;font-size:14px;overflow-x:hidden}" +
            "img{max-width:100%}</style>$html"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            Text(
                "HTML 预览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp)
            )
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                factory = {
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportZoom(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                        }
                    }
                },
                update = { web ->
                    // 只在内容变化时重载，避免重组抖动。
                    if (web.tag != document) {
                        web.tag = document
                        web.loadDataWithBaseURL(
                            null, document, "text/html", "utf-8", null
                        )
                    }
                }
            )
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

/**
 * 数值微条：`{{bar:70}}` → "▕███████░░░▏ 70%"。纯文本，零 WebView / 零自绘，
 * 在等宽视觉下即可读出比例。
 */
private fun renderBar(pct: Int): String {
    val p = pct.coerceIn(0, 100)
    val filled = p / 10
    return "▕" + "█".repeat(filled) + "░".repeat(10 - filled) + "▏ $p%"
}

/* ---------------- 直观表达：原生结构卡 ---------------- */

/**
 * 步骤卡 ```aibox:steps：每行一步，左侧序号圆点 + 竖向连线，Compose 原生绘制、离线可靠。
 */
@Composable
private fun StructSteps(code: String) {
    val steps = code.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            steps.forEachIndexed { idx, line ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${idx + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (idx < steps.lastIndex) Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/**
 * 对比卡 ```aibox:compare：每行形如 `A | B`，渲染成两列；首行若为 `标题A|标题B` 则作表头。
 */
@Composable
private fun StructCompare(code: String) {
    val rows = code.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
        .map { it.split("|").map { c -> c.trim() } }.toList()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            rows.forEachIndexed { idx, cells ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        cells.getOrElse(0) { "" },
                        style = if (idx == 0) MaterialTheme.typography.labelLarge
                        else MaterialTheme.typography.bodyMedium,
                        color = if (idx == 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        cells.getOrElse(1) { "" },
                        style = if (idx == 0) MaterialTheme.typography.labelLarge
                        else MaterialTheme.typography.bodyMedium,
                        color = if (idx == 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 利弊卡 ```aibox:proscons：`+ 优点` / `- 缺点` 分两栏，语义色用中性灰阶区分（+ 用主色、- 用次要色）。
 */
@Composable
private fun StructProsCons(code: String) {
    val lines = code.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    val pros = lines.filter { it.startsWith("+") }.map { it.removePrefix("+").trim() }
    val cons = lines.filter { it.startsWith("-") }.map { it.removePrefix("-").trim() }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("优点", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                pros.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("缺点", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                cons.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

private fun parseInline(
    src: String,
    primary: Color,
    onAskTerm: (String, String) -> Unit = { _, _ -> }
): AnnotatedString = buildAnnotatedString {
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
            // 就地释义词 {{?术语|一句话释义}}：可点击，点开就地看释义（非超链接）
            src.startsWith("{{?", i) -> {
                val end = src.indexOf("}}", i + 3)
                if (end > i + 3) {
                    val inner = src.substring(i + 3, end)
                    val sep = inner.indexOf('|')
                    val term = if (sep >= 0) inner.substring(0, sep) else inner
                    val gloss = if (sep >= 0) inner.substring(sep + 1) else ""
                    if (term.isNotBlank()) {
                        flush()
                        val clickable = LinkAnnotation.Clickable(
                            tag = "term",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = primary,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        ) { onAskTerm(term.trim(), gloss.trim()) }
                        pushLink(clickable)
                        append(term.trim())
                        pop()
                        i = end + 2
                    } else { buffer.append("{{?"); i += 3 }
                } else { buffer.append("{{?"); i += 3 }
            }
            // 数值微条 {{bar:70}}
            src.startsWith("{{bar:", i) -> {
                val end = src.indexOf("}}", i + 6)
                if (end > i + 6) {
                    val pct = src.substring(i + 6, end).trim().toIntOrNull()
                    if (pct != null) {
                        flush()
                        withStyle(SpanStyle(color = primary)) { append(renderBar(pct)) }
                        i = end + 2
                    } else { buffer.append("{{bar:"); i += 6 }
                } else { buffer.append("{{bar:"); i += 6 }
            }
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
