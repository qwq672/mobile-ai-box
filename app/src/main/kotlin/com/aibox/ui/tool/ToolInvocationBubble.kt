package com.aibox.ui.tool

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibox.domain.tool.builtin.SearchResultItem
import com.aibox.ui.theme.Colors

/**
 * 工具调用行（B6 工具调用 UI）。
 *
 * 按需求改成「图标 + 暗色文字」的轻量行，避免和正文抢视觉：
 * - 左侧工具图标（按 [toolId] 映射矢量图标）
 * - 显示名用 onSurfaceVariant，参数摘要更淡
 * - 点整行展开：参数 / 搜索结果 / 完整输出 / 失败重试
 */
@Composable
fun ToolInvocationBubble(
    invocation: ToolInvocation,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember(invocation.id) { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val hits = remember(invocation.result) {
        (invocation.result?.structuredData as? List<*>)?.filterIsInstance<SearchResultItem>() ?: emptyList()
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                toolIcon(invocation.toolId),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = dim.copy(alpha = 0.75f)
            )
            Text(
                invocation.toolDisplayName,
                style = MaterialTheme.typography.labelLarge,
                color = dim,
                maxLines = 1
            )
            Text(
                formatParams(invocation.params),
                style = MaterialTheme.typography.labelSmall,
                color = dim.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            when (invocation.state) {
                ToolState.RUNNING, ToolState.CONFIRMING -> CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = dim
                )
                ToolState.FAILED -> Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = "失败",
                    modifier = Modifier.size(14.dp),
                    tint = Colors.error
                )
                ToolState.SUCCESS -> Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(16.dp),
                    tint = dim.copy(alpha = 0.6f)
                )
            }
        }

        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "参数: ${formatParams(invocation.params)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim.copy(alpha = 0.8f)
                )

                if (hits.isNotEmpty()) {
                    SearchHits(hits)
                } else if (invocation.state == ToolState.SUCCESS) {
                    Text(
                        invocation.result?.output.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                        lineHeight = 18.sp
                    )
                }

                if (invocation.state == ToolState.FAILED) {
                    Text(
                        invocation.error ?: "执行失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = Colors.error
                    )
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
                if (invocation.executionTimeMs > 0) {
                    Text(
                        "耗时 ${formatMs(invocation.executionTimeMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = dim.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

/** 联网搜索 / URL 阅读的结果列表：标题 + 域名 + 摘要，点击打开链接。 */
@Composable
internal fun SearchHits(hits: List<SearchResultItem>) {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        hits.forEachIndexed { index, hit ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uri.openUri(hit.url) } }
            ) {
                Text(
                    "${index + 1}. ${hit.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    hit.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hit.snippet.isNotBlank()) {
                    Text(
                        hit.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 工具 id → 图标（未知工具用扳手）。 */
internal fun toolIcon(toolId: String): ImageVector = when (toolId) {
    "web_search" -> Icons.Outlined.Search
    "url_reader" -> Icons.Outlined.Language
    "datetime" -> Icons.Outlined.Schedule
    "calculator" -> Icons.Outlined.Calculate
    "file_read", "file_write" -> Icons.Outlined.Description
    "email" -> Icons.Outlined.MailOutline
    "phone_control" -> Icons.Outlined.Phone
    "device_info_basic", "device_info_advanced" -> Icons.Outlined.Smartphone
    else -> Icons.Outlined.Build
}

/** 格式化参数 map 为可读字符串。空 map 返回 "(无参数)"。 */
internal fun formatParams(params: Map<String, Any>): String {
    if (params.isEmpty()) return "(无参数)"
    return params.entries.joinToString(", ") { (k, v) ->
        val vs = when (v) {
            is String -> "\"$v\""
            else -> v.toString()
        }
        "$k=$vs"
    }
}

/** 把毫秒格式化为人类可读时长：小于 1s 显示 ms，否则显示 s（带 1 位小数）。 */
internal fun formatMs(ms: Long): String {
    return if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)
}
