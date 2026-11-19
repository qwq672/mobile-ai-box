package com.aibox.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import android.content.ContextWrapper
import com.aibox.AIBoxApp
import com.aibox.data.repo.SettingsRepository

/** 全局圆角倍率：由 设置 → 外观 → 圆角大小 控制。1.0 = 默认。 */
val LocalCornerScale = staticCompositionLocalOf { 1f }

/** 把一个 dp 值按全局圆角倍率缩放（0.5x ~ 1.6x）。 */
@Composable
fun scaledRadius(dp: Int): Int = (dp * LocalCornerScale.current).toInt().coerceAtLeast(2)

// 扩展后的色彩角色（含 error / onSurfaceVariant / surfaceContainer 等常用项）
private val LightColors = lightColorScheme(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF1A2A5E),
    secondary = md_light_secondary,
    onSecondary = md_light_onSecondary,
    tertiary = md_light_tertiary,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8DDFF),
    onTertiaryContainer = Color(0xFF2A0A5E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = md_light_background,
    onBackground = Color(0xFF1A1B1F),
    surface = md_light_surface,
    onSurface = md_light_onSurface,
    onSurfaceVariant = Color(0xFF44474F),
    surfaceVariant = md_light_surfaceVariant,
    surfaceContainer = Color(0xFFEFEFF4),
    surfaceContainerLow = Color(0xFFF5F5FA),
    surfaceContainerHigh = Color(0xFFE9E9EE),
    surfaceContainerHighest = Color(0xFFE0E0E5),
    outline = md_light_outline,
    outlineVariant = Color(0xFFC4C6CF),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFFB6C4FF),
    surfaceTint = md_light_primary,
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = Color(0xFF1B2A5E),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    tertiary = md_dark_tertiary,
    onTertiary = Color(0xFF3A0A6A),
    tertiaryContainer = Color(0xFF5B3A9E),
    onTertiaryContainer = Color(0xFFE8DDFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = md_dark_background,
    onBackground = Color(0xFFE5E6EE),
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceVariant = md_dark_surfaceVariant,
    surfaceContainer = Color(0xFF1C1D24),
    surfaceContainerLow = Color(0xFF16171E),
    surfaceContainerHigh = Color(0xFF202127),
    surfaceContainerHighest = Color(0xFF2B2C33),
    outline = md_dark_outline,
    outlineVariant = Color(0xFF44474F),
    inverseSurface = Color(0xFFE5E6EE),
    inverseOnSurface = Color(0xFF1A1B1F),
    inversePrimary = Color(0xFF2A5FE0),
    surfaceTint = md_dark_primary,
    scrim = Color(0xFF000000),
)

/**
 * 全局 Shapes —— 规范的 4/8/12/16/24 递增梯度，乘以 cornerScale 倍率。
 *
 * 保持原有四档视觉尺寸不变（small=12 / medium=16 / large=24），仅把原先
 * 不规范的 extraLarge=28 规整到 DesignTokens.radiusXLarge=24 之外的新档 32，
 * 形成 8/12/16/24/32 的标准梯度。避免"24 与 28 混用"，同时不造成整体变方。
 */
private fun appShapes(scale: Float): Shapes = Shapes(
    extraSmall = RoundedCornerShape((8 * scale).dp),
    small = RoundedCornerShape((12 * scale).dp),
    medium = RoundedCornerShape((16 * scale).dp),
    large = RoundedCornerShape((24 * scale).dp),
    extraLarge = RoundedCornerShape((32 * scale).dp)
)

/**
 * 由单个种子色推导整套配色（不依赖 Material Color Utilities）。
 *
 * 保持既有的 surface 体系不变，只重算 primary / secondary / tertiary 及其容器色，
 * 这样自定义主色时页面底色、分割线仍然协调。
 */
private fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val h = hsl[0]
    val s = hsl[1].coerceIn(0.15f, 0.9f)

    fun tone(hue: Float, sat: Float, light: Float): Color = Color(
        androidx.core.graphics.ColorUtils.HSLToColor(
            floatArrayOf(((hue % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), light.coerceIn(0f, 1f))
        )
    )

    val base = if (dark) DarkColors else LightColors
    return if (dark) base.copy(
        primary = tone(h, s.coerceAtMost(0.72f), 0.78f),
        onPrimary = tone(h, s, 0.16f),
        primaryContainer = tone(h, s * 0.7f, 0.28f),
        onPrimaryContainer = tone(h, s * 0.5f, 0.92f),
        secondary = tone(h, s * 0.5f, 0.72f),
        onSecondary = tone(h, s, 0.16f),
        secondaryContainer = tone(h, s * 0.5f, 0.28f),
        onSecondaryContainer = tone(h, s * 0.4f, 0.92f),
        tertiary = tone(h + 60f, s * 0.6f, 0.75f),
        onTertiary = tone(h + 60f, s, 0.16f),
        tertiaryContainer = tone(h + 60f, s * 0.5f, 0.3f),
        onTertiaryContainer = tone(h + 60f, s * 0.4f, 0.92f),
        inversePrimary = tone(h, s * 0.7f, 0.42f),
        surfaceTint = tone(h, s.coerceAtMost(0.72f), 0.78f),
        // 让背景/卡片也带一点主色倾向，而不是纯灰
        background = tone(h, 0.12f, 0.09f),
        surface = tone(h, 0.12f, 0.09f),
        surfaceContainerLow = tone(h, 0.12f, 0.11f),
        surfaceContainer = tone(h, 0.13f, 0.13f),
        surfaceContainerHigh = tone(h, 0.14f, 0.16f),
        surfaceContainerHighest = tone(h, 0.15f, 0.20f),
        onBackground = tone(h, 0.08f, 0.94f),
        onSurface = tone(h, 0.08f, 0.94f),
        onSurfaceVariant = tone(h, 0.10f, 0.76f),
        outline = tone(h, 0.14f, 0.60f),
        outlineVariant = tone(h, 0.14f, 0.32f)
    ) else base.copy(
        primary = tone(h, s, 0.4f),
        onPrimary = Color.White,
        primaryContainer = tone(h, s * 0.6f, 0.9f),
        onPrimaryContainer = tone(h, s * 0.8f, 0.16f),
        secondary = tone(h, s * 0.6f, 0.44f),
        onSecondary = Color.White,
        secondaryContainer = tone(h, s * 0.5f, 0.9f),
        onSecondaryContainer = tone(h, s * 0.8f, 0.18f),
        tertiary = tone(h + 60f, s * 0.7f, 0.4f),
        onTertiary = Color.White,
        tertiaryContainer = tone(h + 60f, s * 0.5f, 0.92f),
        onTertiaryContainer = tone(h + 60f, s * 0.8f, 0.18f),
        inversePrimary = tone(h, s * 0.7f, 0.78f),
        surfaceTint = tone(h, s, 0.4f),
        background = tone(h, 0.10f, 0.985f),
        surface = tone(h, 0.10f, 0.985f),
        surfaceContainerLow = tone(h, 0.10f, 0.96f),
        surfaceContainer = tone(h, 0.11f, 0.94f),
        surfaceContainerHigh = tone(h, 0.12f, 0.91f),
        surfaceContainerHighest = tone(h, 0.13f, 0.88f),
        onBackground = tone(h, 0.10f, 0.11f),
        onSurface = tone(h, 0.10f, 0.11f),
        onSurfaceVariant = tone(h, 0.12f, 0.34f),
        outline = tone(h, 0.18f, 0.50f),
        outlineVariant = tone(h, 0.14f, 0.80f)
    )
}

/**
 * 中性 / 黑白主题配色 —— 面向"看不惯 MD3 tonal 配色"的用户。
 *
 * 全部角色严格走灰阶三层（R=G=B，无任何色相）：
 * 1. 中性层：background / surface / surfaceContainer* / onSurface / outline 纯灰阶
 *    （见 Color.kt 的 neutral_* 常量）；
 * 2. 强调层：primary / secondary / tertiary 全部改为灰阶（暗色近白 / 亮色近黑），
 *    **不再使用 SeedBlue 派生任何蓝色**——用户明确要求「黑白主题不使用强调色」。
 *    三级灰按对比度递减排列，容器底色沿用中性 surfaceContainer，on*Container 走灰阶文字；
 * 3. 语义层：error 由 base scheme 原样保留（红色，停止/删除按钮仍是红的），
 *    成功绿 / 警告黄 由 [LocalNeutralAccents] 单独提供，不被灰化。
 *
 * surfaceTint 归零为 transparent，避免滚动 TopAppBar / NavigationBar 叠加 tint 后又泛出主色。
 *
 * 中性主题是「固定黑白主题」，用户不可选配色，因此这里刻意不接收外部 seed；
 * 彩色只允许出现在语义点缀（停止红 / 警告黄 / 成功绿），绝不被中性化。
 */
private fun neutralColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    return if (dark) base.copy(
        // ---- 强调层：全部灰阶，三级对比度递减（无任何色相）----
        primary = neutral_dark_primary,
        onPrimary = neutral_dark_onPrimary,
        primaryContainer = neutral_dark_surfaceContainer,
        onPrimaryContainer = neutral_dark_primary,
        secondary = neutral_dark_secondary,
        onSecondary = neutral_dark_onSecondary,
        secondaryContainer = neutral_dark_surfaceContainerHigh,
        onSecondaryContainer = neutral_dark_secondary,
        tertiary = neutral_dark_tertiary,
        onTertiary = neutral_dark_onTertiary,
        tertiaryContainer = neutral_dark_surfaceContainerHighest,
        onTertiaryContainer = neutral_dark_tertiary,
        inversePrimary = neutral_dark_inversePrimary,
        // ---- 中性层：纯灰阶 ----
        background = neutral_dark_background,
        onBackground = neutral_dark_onSurface,
        surface = neutral_dark_surface,
        onSurface = neutral_dark_onSurface,
        onSurfaceVariant = neutral_dark_onSurfaceVariant,
        surfaceVariant = neutral_dark_surfaceVariant,
        surfaceContainerLow = neutral_dark_surfaceContainerLow,
        surfaceContainer = neutral_dark_surfaceContainer,
        surfaceContainerHigh = neutral_dark_surfaceContainerHigh,
        surfaceContainerHighest = neutral_dark_surfaceContainerHighest,
        outline = neutral_dark_outline,
        outlineVariant = neutral_dark_outlineVariant,
        inverseSurface = neutral_dark_inverseSurface,
        inverseOnSurface = neutral_dark_inverseOnSurface,
        surfaceTint = Color.Transparent,                        // 杜绝 tint 泛色
        scrim = Color.Black
        // error / onError / errorContainer / onErrorContainer 继承 base（红色语义，不动）
    ) else base.copy(
        primary = neutral_light_primary,
        onPrimary = neutral_light_onPrimary,
        primaryContainer = neutral_light_surfaceContainer,
        onPrimaryContainer = neutral_light_primary,
        secondary = neutral_light_secondary,
        onSecondary = neutral_light_onSecondary,
        secondaryContainer = neutral_light_surfaceContainerHigh,
        onSecondaryContainer = neutral_light_secondary,
        tertiary = neutral_light_tertiary,
        onTertiary = neutral_light_onTertiary,
        tertiaryContainer = neutral_light_surfaceContainerHighest,
        onTertiaryContainer = neutral_light_tertiary,
        inversePrimary = neutral_light_inversePrimary,
        background = neutral_light_background,
        onBackground = neutral_light_onSurface,
        surface = neutral_light_surface,
        onSurface = neutral_light_onSurface,
        onSurfaceVariant = neutral_light_onSurfaceVariant,
        surfaceVariant = neutral_light_surfaceVariant,
        surfaceContainerLow = neutral_light_surfaceContainerLow,
        surfaceContainer = neutral_light_surfaceContainer,
        surfaceContainerHigh = neutral_light_surfaceContainerHigh,
        surfaceContainerHighest = neutral_light_surfaceContainerHighest,
        outline = neutral_light_outline,
        outlineVariant = neutral_light_outlineVariant,
        inverseSurface = neutral_light_inverseSurface,
        inverseOnSurface = neutral_light_inverseOnSurface,
        surfaceTint = Color.Transparent,
        scrim = Color.Black
    )
}

/**
 * 语义点缀色的 CompositionLocal。
 *
 * - 值为 null（默认）：非中性主题，DesignTokens 的状态色走原 MaterialRole 桥接；
 * - 非 null：中性黑白主题下专用的少量彩色点缀（成功绿 / 警告黄 / 危险红）。
 *
 * 这样中性主题把 primary 变灰后，「完成/成功」「警告」等状态仍能显示彩色点缀，
 * 而普通 MD3 主题行为不变。
 */
data class NeutralAccents(val success: Color, val warning: Color, val danger: Color)

val LocalNeutralAccents = staticCompositionLocalOf<NeutralAccents?> { null }

private fun parseSeedColor(spec: String): Color? {
    if (spec.isBlank() || spec.equals("dynamic", ignoreCase = true)) return null
    return runCatching { Color(android.graphics.Color.parseColor(spec)) }.getOrNull()
}

@Composable
fun AIBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    accentColor: String = "dynamic",
    cornerScale: Float = 1f,
    neutralTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val seed = parseSeedColor(accentColor)
    val colorScheme = when {
        // 中性/黑白主题最高优先级：强制忽略动态取色与用户主色调，彻底脱离 MD3 tonal 配色。
        // 强调色全灰阶，仅保留语义点缀色（停止红/警告黄/成功绿），配色不可选。
        neutralTheme -> neutralColorScheme(darkTheme)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        seed != null -> schemeFromSeed(seed, darkTheme)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // view.context 可能是 ContextWrapper（如 ContextThemeWrapper），不一定是 Activity
            var ctx = view.context
            while (ctx is ContextWrapper) {
                if (ctx is android.app.Activity) break
                ctx = ctx.baseContext
            }
            val activity = ctx as? android.app.Activity ?: return@SideEffect
            val window = activity.window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // 中性主题下把语义点缀色（成功绿/警告黄/危险红）注入，供 DesignTokens 状态色使用；
    // 非中性主题传 null，DesignTokens 走原 MaterialRole 桥接，行为完全不变。
    val neutralAccents = if (neutralTheme) {
        if (darkTheme) {
            NeutralAccents(neutral_dark_success, neutral_dark_warning, neutral_dark_danger)
        } else {
            NeutralAccents(neutral_light_success, neutral_light_warning, neutral_light_danger)
        }
    } else null

    CompositionLocalProvider(
        LocalCornerScale provides cornerScale,
        LocalNeutralAccents provides neutralAccents,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PocketTypography,
            shapes = appShapes(cornerScale),
            content = content
        )
    }
}

/**
 * 从全局 SettingsRepository 读 darkTheme / dynamicColor / accentColor / cornerScale / neutralTheme 自动配置。
 * 用在 MainActivity 的根 composable。
 */
@Composable
fun AIBoxThemeFromSettings(content: @Composable () -> Unit) {
    val app = AIBoxApp.instance
    val settings: SettingsRepository = app.container.settingsRepository
    val darkThemeMode by settings.darkTheme.collectAsState(initial = "system")
    val dynamicColor by settings.dynamicColor.collectAsState(initial = true)
    val accentColor by settings.accentColor.collectAsState(initial = "dynamic")
    val cornerScale by settings.cornerScale.collectAsState(initial = 1f)
    val neutralTheme by settings.neutralTheme.collectAsState(initial = false)

    val isDark = when (darkThemeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    AIBoxTheme(
        darkTheme = isDark,
        dynamicColor = dynamicColor,
        accentColor = accentColor,
        cornerScale = cornerScale,
        neutralTheme = neutralTheme,
        content = content
    )
}
