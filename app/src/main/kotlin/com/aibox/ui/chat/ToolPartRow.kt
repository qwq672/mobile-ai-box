package com.aibox.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibox.data.model.MsgPart
import com.aibox.domain.tool.builtin.SearchResultItem
import com.aibox.ui.theme.Colors
import com.aibox.ui.tool.SearchHits
import com.aibox.ui.tool.toolIcon

/**
 * 消息时间线里的工具调用行。
 *
 * 与 [com.aibox.ui.tool.ToolInvocationBubble] 同一视觉语言：图标 + 暗色文字，
 * 点开看参数 / 搜索结果 / 输出。搜索结果默认也在折叠区里，避免长输出打断正文。
 */
@Composable
fun ToolPartRow(part: MsgPart) {
    var expanded by remember(part.toolName, part.output) { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val hits = remember(part.hits) {
        part.hits.map { SearchResultItem(it.title, it.url, it.snippet) }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                toolIcon(part.toolId),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = dim.copy(alpha = 0.75f)
            )
            Text(
                part.toolName.ifBlank { part.toolId },
                style = MaterialTheme.typography.labelLarge,
                color = dim,
                maxLines = 1
            )
            Text(
                part.args,
                style = MaterialTheme.typography.labelSmall,
                color = dim.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            when {
                part.running -> CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = dim
                )
                !part.success -> Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = "失败",
                    modifier = Modifier.size(14.dp),
                    tint = Colors.error
                )
                else -> Icon(
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
                if (part.args.isNotBlank()) {
                    Text(
                        "参数: ${part.args}",
                        style = MaterialTheme.typography.bodySmall,
                        color = dim.copy(alpha = 0.8f)
                    )
                }
                if (hits.isNotEmpty()) {
                    SearchHits(hits)
                } else if (part.output.isNotBlank()) {
                    Text(
                        part.output,
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
