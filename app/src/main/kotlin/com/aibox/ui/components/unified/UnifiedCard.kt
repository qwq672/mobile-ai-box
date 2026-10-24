package com.aibox.ui.components.unified

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens

/**
 * 统一卡片容器的色调枚举。
 *
 * 通过枚举而非 `Color` 参数强制组件层不能传任意颜色，
 * 只能从宿主预定义的 Material surface 色阶中选一个。
 */
enum class UnifiedCardTone {
    /** [Colors.surface]，最基础卡片色。 */
    SURFACE,

    /** [Colors.surfaceContainerLow]，浅列表卡片。 */
    SURFACE_LOW,

    /** [Colors.surfaceContainerHigh]，强调卡片。 */
    SURFACE_HIGH,

    /** [Colors.primaryContainer]，强调主题色（如激活态模型卡片）。 */
    PRIMARY_CONTAINER
}

/**
 * 统一卡片容器。所有插件设置 / 列表项的容器必须通过此组件渲染。
 *
 * 设计约束（强制使用 Design Token）：
 * - **不接受任意颜色参数**。背景色只能通过 [tone] 枚举选择，由 [Colors] 解析。
 * - 圆角 / 阴影 / 内边距都从 [DesignTokens] 取默认值，调用方只能传结构性参数。
 * - 支持 clickable / non-clickable 两种模式（[onClick] 为 null 则纯展示）。
 *
 * 视觉规则：
 * - 默认圆角 [DesignTokens.shapeLarge]（12dp）
 * - 默认阴影 [DesignTokens.elevation1]（1dp，扁平卡片）
 * - 默认内边距 [DesignTokens.spacing16]
 *
 * @param tone 卡片背景色调，见 [UnifiedCardTone]
 * @param elevation 阴影高度，默认 [DesignTokens.elevation1]
 * @param shape 圆角 Shape，默认 [DesignTokens.shapeLarge]
 * @param contentPadding 内边距，默认 [DesignTokens.spacing16] 四边
 * @param onClick 若提供则渲染为可点击 Card（带 ripple），否则为静态卡片
 * @param modifier 外部布局修饰符
 * @param content 卡片内容
 */
@Composable
fun UnifiedCard(
    modifier: Modifier = Modifier,
    tone: UnifiedCardTone = UnifiedCardTone.SURFACE,
    elevation: Dp = DesignTokens.elevation1,
    shape: Shape = DesignTokens.shapeLarge,
    contentPadding: PaddingValues = PaddingValues(DesignTokens.spacing16),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val containerColor = when (tone) {
        UnifiedCardTone.SURFACE -> Colors.surface
        UnifiedCardTone.SURFACE_LOW -> Colors.surfaceContainerLow
        UnifiedCardTone.SURFACE_HIGH -> Colors.surfaceContainerHigh
        UnifiedCardTone.PRIMARY_CONTAINER -> Colors.primaryContainer
    }
    val cardColors = CardDefaults.cardColors(containerColor = containerColor)
    val cardElevation = CardDefaults.cardElevation(defaultElevation = elevation)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = cardColors,
            elevation = cardElevation
        ) {
            Column(Modifier.padding(contentPadding)) { content() }
        }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = cardColors,
            elevation = cardElevation
        ) {
            Column(Modifier.padding(contentPadding)) { content() }
        }
    }
}
