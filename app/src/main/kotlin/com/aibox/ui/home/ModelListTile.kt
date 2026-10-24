package com.aibox.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aibox.data.model.ModelInfo
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 主页"最近模型"列表项（E 主页 Hybrid 设计）。
 *
 * 渲染一行：模型架构图标 + 模型名 + (大小 · 架构 · 量化) 副标题。
 *
 * 视觉：[UnifiedCard]（SURFACE_LOW），padding 16dp；图标盒 40x40（primaryContainer 背景 + shapeSmall）；
 * 标题 titleSmall / SsemiBold；副标题 labelSmall / onSurfaceVariant。
 *
 * @param model 模型信息
 * @param onClick 点击回调（跳到模型详情页 / 加载该模型）
 * @param modifier 外部布局修饰符
 */
@Composable
fun ModelListTile(
    model: ModelInfo,
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
            // 模型架构图标盒（40dp x 40dp，与 ModelsScreen 的 ModelCard 视觉一致）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = Colors.primaryContainer,
                        shape = DesignTokens.shapeSmall
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = Colors.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = model.name,
                    style = Type.titleSmall,
                    color = Colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(formatSize(model.sizeMb))
                        model.arch?.let { append(" · ").append(it) }
                        model.quant?.let { append(" · ").append(it) }
                    },
                    style = Type.labelSmall,
                    color = Colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 模型大小格式化：>= 1024 MB 显示 GB，否则 MB。 */
private fun formatSize(mb: Long): String =
    if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "$mb MB"
