package com.aibox.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 主页"功能区"卡片（E 主页 Hybrid 设计）。
 *
 * 渲染一行：Material 图标 + 标题 + 副标题 + 右侧 chevron。
 * 整张卡片可点击 → 跳转到对应功能页。
 *
 * 视觉整改（MD3）：emoji 改为带色底的 [ImageVector] 图标容器，
 * 与系统 Material 图标体系一致；容器用 primaryContainer 着色，图标用 primary，
 * 圆角走 [DesignTokens] 统一 shape（不再是裸 emoji）。
 *
 * @param title 主标题，如 "聊天"
 * @param icon Material 图标向量，如 Chat / Mic / Settings 等 outlined 图标
 * @param subtitle 副标题，如 "5 个模型已加载"
 * @param onClick 点击回调
 * @param modifier 外部布局修饰符
 */
@Composable
fun FunctionTile(
    title: String,
    icon: ImageVector,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    UnifiedCard(
        modifier = modifier.fillMaxWidth(),
        tone = UnifiedCardTone.SURFACE_LOW,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = Colors.primaryContainer,
                        shape = DesignTokens.shapeMedium
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Colors.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = Type.titleMedium,
                    color = Colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = Type.bodySmall,
                    color = Colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = Colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
