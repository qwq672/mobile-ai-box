package com.aibox.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AIBox 宿主唯一的 Design Token 资产。
 *
 * 这是 P0.2 UI 统一基础的核心契约：**所有插件 UI 必须通过本 object 取色/字/间距/圆角等，
 * 不允许直接硬编码颜色（`Color(0xFF...)`）或字号 `dp/sp` 字面量。**
 *
 * 设计原则：
 * 1. 静态 token（spacing / radius / elevation / duration / motion / shape）—— 直接 `val`，不依赖主题。
 * 2. 动态 token（color / typography）—— 通过 [Colors] / [Type] 这两个 object 上的
 *    `@Composable @ReadOnlyComposable` 属性读取 [MaterialTheme.colorScheme] / [MaterialTheme.typography]，
 *    自动适配深色 / 浅色主题。
 *
 * 与现有 [Theme.kt] / [Color.kt] / [Type.kt] 的关系：
 * - 颜色 / 字体的具体值仍由 [Theme.kt] 注入 MaterialTheme；本文件不重复定义色值，
 *   只做"命名 token → MaterialTheme 资产"的桥接，确保组件层不需要直接 `MaterialTheme.colorScheme.X`。
 * - 间距 / 圆角 / 阴影 / 动画时长等"非动态"维度，在此集中声明，作为全局唯一来源。
 *
 * @see Colors
 * @see Type
 */
object DesignTokens {

    // ---------- 间距 ----------
    /** 4dp。最小间距，用于 chip 内边距、图标与文字紧贴场景。 */
    val spacing4: Dp = 4.dp
    /** 8dp。常规小间距，用于卡片内元素之间。 */
    val spacing8: Dp = 8.dp
    /** 12dp。中等间距，用于卡片内边距、行内图标与标题间距。 */
    val spacing12: Dp = 12.dp
    /** 16dp。基础间距，用于页面水平边距、ListItem 主体缩进。 */
    val spacing16: Dp = 16.dp
    /** 24dp。段落间距，用于 section 之间。 */
    val spacing24: Dp = 24.dp
    /** 32dp。大间距，用于页面分区之间。 */
    val spacing32: Dp = 32.dp

    // ---------- 圆角 ----------
    /** 4dp。小圆角，用于 chip / 标签。 */
    val radiusSmall: Dp = 4.dp
    /** 8dp。中等圆角，用于按钮 / 输入框。 */
    val radiusMedium: Dp = 8.dp
    /** 12dp。大圆角，用于卡片 / 容器。 */
    val radiusLarge: Dp = 12.dp
    /** 24dp。超大圆角，用于底部弹片 / 大圆卡片。 */
    val radiusXLarge: Dp = 24.dp

    /** 预先实例化的圆角 Shape，避免每帧重建 [RoundedCornerShape]。 */
    val shapeSmall = RoundedCornerShape(radiusSmall)
    val shapeMedium = RoundedCornerShape(radiusMedium)
    val shapeLarge = RoundedCornerShape(radiusLarge)
    val shapeXLarge = RoundedCornerShape(radiusXLarge)

    // ---------- 阴影 ----------
    /** 0dp。无阴影（用于扁平卡片）。 */
    val elevation0: Dp = 0.dp
    /** 1dp。低阴影（默认卡片）。 */
    val elevation1: Dp = 1.dp
    /** 2dp。中阴影（悬停态）。 */
    val elevation2: Dp = 2.dp
    /** 4dp。较高阴影（弹出层 / Dialog）。 */
    val elevation4: Dp = 4.dp
    /** 8dp。高阴影（悬浮按钮 / 模态底部弹片）。 */
    val elevation8: Dp = 8.dp

    // ---------- 动画时长（毫秒） ----------
    /** 150ms。短动画，用于 ripple / state 切换。 */
    const val durationShort: Int = 150
    /** 250ms。中等动画，用于展开 / 折叠。 */
    const val durationMedium: Int = 250
    /** 400ms。长动画，用于页面过渡。 */
    const val durationLong: Int = 400

    // ---------- 缓动函数 ----------
    /** Material 标准缓动。多数场景默认使用。 */
    val easingStandard: Easing = FastOutSlowInEasing
    /** Material Emphasized 缓动，强调入场 / 强动效。 */
    val easingEmphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    /** 减速缓动，用于入场。 */
    val easingDecelerate: Easing = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)
    /** 加速缓动，用于退场。 */
    val easingAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)
}

/**
 * 颜色 token。**所有 UI 颜色必须从这里取，禁止直接 `Color(0xFF...)`。**
 *
 * 每个 token 都桥接到当前 [MaterialTheme.colorScheme] 对应角色，
 * 因此深色 / 浅色主题自动适配，无需在调用方判断 `isSystemInDarkTheme()`。
 *
 * 用法示例：
 * ```
 * Text(text = "...", color = Colors.primary)
 * Box(modifier = Modifier.background(Colors.surfaceContainerLow))
 * ```
 */
object Colors {
    val primary: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val onPrimary: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary
    val primaryContainer: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimaryContainer

    val secondary: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondary
    val onSecondary: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSecondary
    val tertiary: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary
    val onTertiary: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onTertiary

    val background: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
    val onBackground: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onBackground

    val surface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
    val onSurface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
    val surfaceContainerLow: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerLow
    val surfaceContainer: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
    val surfaceContainerHigh: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceContainerHighest: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest

    val error: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
    val onError: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onError
    val errorContainer: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.errorContainer
    val onErrorContainer: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onErrorContainer

    val outline: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline
    val outlineVariant: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
}

/**
 * 字体 token。直接桥到 [MaterialTheme.typography]（实际值见 [Type.kt] / [PocketTypography]）。
 *
 * 命名采用语义化角色（heading / title / body / label）+ 尺寸（Large / Medium / Small），
 * 组件层禁止再 `fontSize = 14.sp` 这种字面量。
 */
object Type {
    val headingLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineLarge
    val headingMedium: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineMedium
    val headingSmall: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineSmall

    val titleLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleLarge
    val titleMedium: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium
    val titleSmall: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleSmall

    val bodyLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge
    val bodyMedium: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium
    val bodySmall: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall

    val labelLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge
    val labelMedium: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium
    val labelSmall: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelSmall
}
