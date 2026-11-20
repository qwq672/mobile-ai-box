package com.aibox.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toDp
import kotlin.math.max

/**
 * 自研思维导图（不依赖 mermaid.js / WebView）。
 *
 * 解析 mermaid `mindmap` 的缩进树语法，用 tidy-tree 简化布局：
 * 根在上、子节点向下展开，内部节点水平居中于其子节点；
 * 圆角矩形节点 + 二次贝塞尔连线，全部用 Compose Canvas 绘制。
 * 颜色取自主题的灰阶 role，不使用蓝色强调色，深浅主题自动适配。
 */
@Composable
fun MindMapCanvas(code: String, modifier: Modifier = Modifier) {
    val tree = remember(code) { parseMindMap(code) }
    if (tree == null) {
        // 不是可识别的 mindmap：原样显示，保证内容不丢。
        Text(
            code,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val textStyle = TextStyle(fontSize = 13.sp, color = cs.onSurface, textAlign = TextAlign.Center)

    val layout = remember(tree, measurer, density, cs, textStyle) {
        val nodeH = with(density) { 40.dp.toPx() }
        val vGap = with(density) { 30.dp.toPx() }
        val hGap = with(density) { 18.dp.toPx() }
        val padH = with(density) { 28.dp.toPx() }
        val minW = with(density) { 78.dp.toPx() }

        var maxTextW = 0f
        fun measure(n: MmNode) {
            maxTextW = max(maxTextW, measurer.measure(n.text, textStyle).size.width.toFloat())
            n.children.forEach(::measure)
        }
        measure(tree)
        val nodeW = max(minW, maxTextW + padH * 2)

        var leafCursor = 0
        var maxDepth = 0
        fun place(n: MmNode, depth: Int): MmPlaced {
            maxDepth = max(maxDepth, depth)
            val p = MmPlaced(n)
            p.y = depth * (nodeH + vGap) + nodeH / 2
            if (n.children.isEmpty()) {
                p.x = leafCursor * (nodeW + hGap) + nodeW / 2
                leafCursor++
            } else {
                val kids = n.children.map { place(it, depth + 1) }
                p.children.addAll(kids)
                p.x = (kids.first().x + kids.last().x) / 2
            }
            return p
        }
        val root = place(tree, 0)
        val widthPx = leafCursor * (nodeW + hGap)
        val heightPx = (maxDepth + 1) * (nodeH + vGap)
        MmLayout(root, nodeW, nodeH, widthPx, heightPx)
    }

    Column(modifier) {
        Text(
            "思维导图",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
        ) {
            Canvas(
                Modifier.size(
                    with(density) { layout.widthPx.toDp() },
                    with(density) { layout.heightPx.toDp() }
                )
            ) {
                val edge = cs.outline
                val border = cs.outlineVariant
                val rootFill = cs.surfaceContainerHighest
                val nodeFill = cs.surfaceContainerLow

                fun drawTree(p: MmPlaced, isRoot: Boolean) {
                    for (ch in p.children) {
                        val start = Offset(p.x, p.y + layout.nodeH / 2)
                        val end = Offset(ch.x, ch.y - layout.nodeH / 2)
                        val midY = (start.y + end.y) / 2
                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            quadraticBezierTo(start.x, midY, end.x, end.y)
                        }
                        drawPath(path, color = edge, style = Stroke(width = 2f))
                        drawTree(ch, false)
                    }
                    val left = p.x - layout.nodeW / 2
                    val top = p.y - layout.nodeH / 2
                    val corner = CornerRadius(12f)
                    drawRoundRect(
                        color = if (isRoot) rootFill else nodeFill,
                        topLeft = Offset(left, top),
                        size = Size(layout.nodeW, layout.nodeH),
                        cornerRadius = corner
                    )
                    drawRoundRect(
                        color = border,
                        topLeft = Offset(left, top),
                        size = Size(layout.nodeW, layout.nodeH),
                        cornerRadius = corner,
                        style = Stroke(width = 1.5f)
                    )
                    val tl = measurer.measure(p.node.text, textStyle)
                    drawText(
                        measurer,
                        p.node.text,
                        topLeft = Offset(
                            left + (layout.nodeW - tl.size.width) / 2,
                            top + (layout.nodeH - tl.size.height) / 2
                        ),
                        style = textStyle
                    )
                }
                drawTree(layout.root, true)
            }
        }
    }
}

private data class MmNode(val text: String, val children: MutableList<MmNode> = mutableListOf())
private class MmPlaced(val node: MmNode) {
    var x = 0f
    var y = 0f
    val children = mutableListOf<MmPlaced>()
}
private data class MmLayout(
    val root: MmPlaced,
    val nodeW: Float,
    val nodeH: Float,
    val widthPx: Float,
    val heightPx: Float
)

/** 解析 mermaid mindmap 缩进树。返回 null 表示不是 mindmap。 */
private fun parseMindMap(code: String): MmNode? {
    val lines = code.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.isEmpty() || !lines.first().trim().equals("mindmap", ignoreCase = true)) return null

    var root: MmNode? = null
    // 栈元素：缩进宽度 -> 该缩进对应的节点
    val stack = mutableListOf<Pair<Int, MmNode>>()
    for (raw in lines.drop(1)) {
        val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
        val label = cleanMindLabel(raw)
        if (label.isBlank()) continue
        val node = MmNode(label)
        if (root == null) {
            root = node
            stack.add(indent to node)
        } else {
            while (stack.isNotEmpty() && stack.last().first >= indent) {
                stack.removeAt(stack.lastIndex)
            }
            if (stack.isEmpty()) {
                root.children.add(node)
                stack.add(indent to node)
            } else {
                stack.last().second.children.add(node)
                stack.add(indent to node)
            }
        }
    }
    return root
}

/** 剥掉 mermaid 形状语法与 icon 修饰，取纯文本。 */
private fun cleanMindLabel(raw: String): String {
    var s = raw.trim()
    s = s.replace(Regex("""::[A-Za-z0-9_]+\([^)]*\)"""), "").trim()
    s = s.replace(Regex("""::[A-Za-z0-9_]+"""), "").trim()
    val shapes = listOf(
        Regex("""^.*?\(\((.+)\)\)$"""),   // ((圆))
        Regex("""^.*?\[(.+)\]$"""),        // [方]
        Regex("""^.*?\{(.+)\}$"""),        // {菱}
        Regex("""^.*?\((.+)\)$""")         // (圆角)
    )
    for (r in shapes) {
        val m = r.find(s)
        if (m != null && m.groupValues[1].isNotBlank()) return m.groupValues[1].trim()
    }
    return s
}
