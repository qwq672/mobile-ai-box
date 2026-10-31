package com.aibox.ui.shortcut

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aibox.domain.shortcut.Shortcut
import com.aibox.domain.shortcut.ShortcutTargetType
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 快捷方式横滑列表组件（E 主页 Hybrid 设计）。
 *
 * 渲染一组 [Shortcut] 卡片 + 末尾的"添加"卡片；卡片固定大小（[CARD_WIDTH] x [CARD_HEIGHT]）。
 *
 * 交互：
 * - 单击卡片：触发 [onShortcutClick]，由 HomeScreen 按 [Shortcut.targetType] 分发到对应功能页。
 * - 长按卡片：触发 [onLongPress]，由 HomeScreen 跳到 [ShortcutEditorScreen] 编辑此快捷方式。
 * - 单击末尾"+"卡片：触发 [onAddShortcutClick]，跳到编辑器新建。
 *
 * 视觉：用 [UnifiedCard]（SURFACE_LOW），保持与 Settings 等页一致；emoji 字号 headlineMedium，标题 labelMedium。
 *
 * @param shortcuts 快捷方式列表（已按 lastUsedAt 排序）
 * @param onShortcutClick 单击回调
 * @param onAddShortcutClick "+" 卡片单击回调
 * @param onLongPress 长按回调
 * @param modifier 外部布局修饰符
 */
@Composable
fun ShortcutRow(
    shortcuts: List<Shortcut>,
    onShortcutClick: (Shortcut) -> Unit,
    onAddShortcutClick: () -> Unit,
    onLongPress: (Shortcut) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = DesignTokens.spacing16,
            vertical = DesignTokens.spacing8
        ),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
    ) {
        items(items = shortcuts, key = { it.id }) { shortcut ->
            ShortcutCard(
                shortcut = shortcut,
                onClick = { onShortcutClick(shortcut) },
                onLongPress = { onLongPress(shortcut) }
            )
        }
        item(key = "add_shortcut") {
            AddShortcutCard(onClick = onAddShortcutClick)
        }
    }
}

/**
 * 单个快捷方式卡片。固定 96dp x 120dp。
 *
 * 单击直接执行快捷方式，长按进入编辑器。用 [UnifiedCard] 的 modifier + [combinedClickable]
 * 同时挂上两种点击行为（UnifiedCard 的 onClick 参数只能接受单击，故用 modifier 而非 onClick 参数）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortcutCard(
    shortcut: Shortcut,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    UnifiedCard(
        modifier = modifier
            .size(width = CARD_WIDTH, height = CARD_HEIGHT)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        tone = UnifiedCardTone.SURFACE_LOW,
        contentPadding = PaddingValues(
            horizontal = DesignTokens.spacing8,
            vertical = DesignTokens.spacing8
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = shortcut.icon.ifBlank { ShortcutTargetType.defaultIcon(shortcut.targetType) },
                style = Type.headingMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(DesignTokens.spacing8))
            Text(
                text = shortcut.title,
                style = Type.labelMedium,
                color = Colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (!shortcut.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = shortcut.subtitle,
                    style = Type.labelSmall,
                    color = Colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * "添加快捷方式"卡片。视觉上比常规卡片虚化（用更弱色调 + dashed 视觉由图标暗示）。
 */
@Composable
fun AddShortcutCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    UnifiedCard(
        modifier = modifier.size(width = CARD_WIDTH, height = CARD_HEIGHT),
        tone = UnifiedCardTone.SURFACE_HIGH,
        contentPadding = PaddingValues(
            horizontal = DesignTokens.spacing8,
            vertical = DesignTokens.spacing8
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "添加快捷方式",
                tint = Colors.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(DesignTokens.spacing8))
            Text(
                text = "添加快捷方式",
                style = Type.labelMedium,
                color = Colors.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 卡片默认尺寸。 */
private val CARD_WIDTH = 96.dp
private val CARD_HEIGHT = 120.dp
