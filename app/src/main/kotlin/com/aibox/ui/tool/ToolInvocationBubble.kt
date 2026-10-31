package com.aibox.ui.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 工具调用气泡卡片（B6 工具调用 UI）。
 *
 * 在聊天消息流中渲染一次工具调用的进度 / 结果 / 错误。
 *
 * 视觉规则：
 * - 卡片色调随 [ToolInvocation.state] 切换：
 *   - [ToolState.CONFIRMING] → [UnifiedCardTone.PRIMARY_CONTAINER]（强调需用户操作）
 *   - [ToolState.RUNNING] → [UnifiedCardTone.SURFACE_HIGH]（活跃高亮）
 *   - [ToolState.SUCCESS] → [UnifiedCardTone.SURFACE_LOW]（默认平静）
 *   - [ToolState.FAILED] → [UnifiedCardTone.SURFACE_HIGH]（保持醒目 + 错误文字用 [Colors.error]）
 * - 顶部一行：工具图标 + 显示名 + 状态 chip（[StateChip]）
 * - 中部：参数预览（[formatParams]）+ 进度条（仅 RUNNING）/ 结果摘要或完整输出（仅 SUCCESS）/ 错误（仅 FAILED）
 * - 失败时显示"重试"按钮（[onRetry] 非 null）
 * - 成功时点卡片或"展开"按钮可切换 摘要 / 完整输出
 *
 * @param invocation 工具调用数据
 * @param onRetry 失败时点"重试"回调；null 表示不支持重试（隐藏按钮）
 * @param modifier 外部布局修饰符
 */
@Composable
fun ToolInvocationBubble(
    invocation: ToolInvocation,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember(invocation.id) { mutableStateOf(false) }

    val tone = when (invocation.state) {
        ToolState.CONFIRMING -> UnifiedCardTone.PRIMARY_CONTAINER
        ToolState.RUNNING -> UnifiedCardTone.SURFACE_HIGH
        ToolState.SUCCESS -> UnifiedCardTone.SURFACE_LOW
        ToolState.FAILED -> UnifiedCardTone.SURFACE_HIGH
    }

    UnifiedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        tone = tone
    ) {
        // 顶部：图标 + 工具名 + 状态 chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
        ) {
            Text(
                text = invocation.toolIcon,
                style = Type.titleMedium
            )
            Text(
                text = invocation.toolDisplayName,
                style = Type.titleSmall,
                color = Colors.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            StateChip(state = invocation.state)
        }

        // 中部：参数预览
        if (invocation.params.isNotEmpty()) {
            Spacer(Modifier.height(DesignTokens.spacing4))
            Text(
                text = "参数: ${formatParams(invocation.params)}",
                style = Type.bodySmall,
                color = Colors.onSurfaceVariant,
                maxLines = if (expanded) 6 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 进度条（执行中）
        if (invocation.state == ToolState.RUNNING) {
            Spacer(Modifier.height(DesignTokens.spacing8))
            val progress = invocation.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // 不确定进度：LinearProgressIndicator 无参版自动跑马灯
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        // 结果（执行完成）
        if (invocation.state == ToolState.SUCCESS && invocation.result != null) {
            Spacer(Modifier.height(DesignTokens.spacing8))
            val output = invocation.result.output
            val display = if (expanded) {
                output
            } else {
                if (output.length > OUTPUT_SUMMARY_LIMIT) {
                    output.take(OUTPUT_SUMMARY_LIMIT) + "…"
                } else {
                    output
                }
            }
            Text(
                text = display,
                style = Type.bodySmall,
                color = Colors.onSurface
            )
            if (output.length > OUTPUT_SUMMARY_LIMIT) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 0.dp,
                        vertical = 0.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(DesignTokens.spacing4))
                    Text(if (expanded) "收起" else "展开全部 ${output.length} 字")
                }
            }
            if (invocation.executionTimeMs > 0) {
                Spacer(Modifier.height(DesignTokens.spacing4))
                Text(
                    text = "耗时 ${formatMs(invocation.executionTimeMs)}",
                    style = Type.labelSmall,
                    color = Colors.onSurfaceVariant
                )
            }
        }

        // 错误（失败）
        if (invocation.state == ToolState.FAILED) {
            Spacer(Modifier.height(DesignTokens.spacing8))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = Colors.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = invocation.error ?: "执行失败",
                    style = Type.bodySmall,
                    color = Colors.error,
                    modifier = Modifier.weight(1f)
                )
                if (onRetry != null) {
                    TextButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(DesignTokens.spacing4))
                        Text("重试")
                    }
                }
            }
        }
    }
}

/**
 * 状态 chip：紧凑展示工具调用当前状态（B6）。
 *
 * 视觉规则：
 * - 用一个小圆角 Surface + 图标 + 文字
 * - 颜色按状态语义派发：CONFIRMING / RUNNING 用 primary，SUCCESS 用 primary（已完成），FAILED 用 error
 */
@Composable
private fun StateChip(state: ToolState) {
    val (text, color, icon) = when (state) {
        ToolState.CONFIRMING -> Triple("待确认", Colors.primary, Icons.Outlined.HourglassEmpty)
        ToolState.RUNNING -> Triple("执行中", Colors.primary, Icons.Outlined.HourglassEmpty)
        ToolState.SUCCESS -> Triple("已完成", Colors.primary, Icons.Outlined.CheckCircle)
        ToolState.FAILED -> Triple("失败", Colors.error, Icons.Outlined.ErrorOutline)
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(DesignTokens.radiusSmall)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = DesignTokens.spacing8,
                vertical = DesignTokens.spacing4
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing4)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = text,
                style = Type.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
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

/** 摘要输出截断阈值（字符数）。超出此长度的输出在折叠态显示前 [OUTPUT_SUMMARY_LIMIT] 字符 + "…"。 */
private const val OUTPUT_SUMMARY_LIMIT = 200
