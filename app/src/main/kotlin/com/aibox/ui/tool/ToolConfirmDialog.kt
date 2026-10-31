package com.aibox.ui.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 高级工具执行前的确认对话框（B6 工具调用 UI）。
 *
 * 当 [ToolInvocationViewModel.pendingConfirmation] 非空时由 [com.aibox.ui.chat.ChatScreen] 渲染。
 * 用户可选：
 * - **确认执行**：本次允许，下次仍弹此 dialog
 * - **始终允许此工具**：调 [onAlwaysAllow]，把此工具加入自动确认白名单（[com.aibox.domain.tool.ToolSettingsRepository.setAutoConfirm]）
 * - **拒绝**：本次拒绝，[ToolInvocation] 状态推进到 FAILED，error="User declined"
 *
 * 视觉规则：
 * - 标题 "确认执行高级工具" + 警告图标
 * - 工具图标 + 显示名 + 警告色说明
 * - 参数列表（key: value，每行一条）
 * - 底部三按钮：拒绝 / 始终允许 / 确认执行（确认为主按钮）
 *
 * @param pending 待确认的工具调用（含 invocationId / 工具元信息 / 参数）
 * @param onConfirm 用户点"确认执行"
 * @param onDeny 用户点"拒绝"
 * @param onAlwaysAllow 用户点"始终允许此工具"（写入自动确认白名单）
 */
@Composable
fun ToolConfirmDialog(
    pending: PendingToolConfirmation,
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
    onAlwaysAllow: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = Colors.error
            )
        },
        title = {
            Text(
                text = "确认执行高级工具",
                style = Type.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
            ) {
                // 工具图标 + 显示名
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                ) {
                    Text(
                        text = pending.toolIcon,
                        style = Type.titleMedium
                    )
                    Text(
                        text = pending.toolDisplayName,
                        style = Type.titleSmall,
                        color = Colors.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "工具将使用以下参数执行:",
                    style = Type.bodySmall,
                    color = Colors.onSurfaceVariant
                )

                // 参数列表：用 LazyColumn 限制最大高度，避免参数过多时撑爆 dialog
                if (pending.params.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(
                                (pending.params.size.coerceAtMost(MAX_PARAM_ROWS) *
                                    PARAM_ROW_HEIGHT_DP).dp
                            )
                    ) {
                        items(items = pending.params.entries.toList()) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "${entry.key}:",
                                    style = Type.bodySmall,
                                    color = Colors.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.widthIn(max = 96.dp)
                                )
                                Spacer(Modifier.width(DesignTokens.spacing8))
                                Text(
                                    text = formatParamValue(entry.value),
                                    style = Type.bodySmall,
                                    color = Colors.onSurface
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "(无参数)",
                        style = Type.bodySmall,
                        color = Colors.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(DesignTokens.spacing4))

                // 警告说明
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                ) {
                    Text(
                        text = "⚠️",
                        style = Type.bodySmall
                    )
                    Text(
                        text = "此工具可能影响设备状态或数据,请确认。",
                        style = Type.bodySmall,
                        color = Colors.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认执行", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing4)
            ) {
                TextButton(onClick = onAlwaysAllow) {
                    Text("始终允许此工具", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onDeny) {
                    Text("拒绝", color = Colors.error)
                }
            }
        }
    )
}

/** 把参数值格式化为可读字符串：String 加引号，其它原样 toString。 */
private fun formatParamValue(value: Any): String = when (value) {
    is String -> "\"$value\""
    else -> value.toString()
}

/** 单个参数行预估高度（dp），用于限制 LazyColumn 高度避免参数过多撑爆 dialog。 */
private const val PARAM_ROW_HEIGHT_DP: Int = 22

/** 参数列表最大展示行数（超出滚动）。 */
private const val MAX_PARAM_ROWS: Int = 6
