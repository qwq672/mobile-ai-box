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

private fun appShapes(scale: Float): Shapes = Shapes(
    extraSmall = RoundedCornerShape((8 * scale).dp),
    small = RoundedCornerShape((12 * scale).dp),
    medium = RoundedCornerShape((16 * scale).dp),
    large = RoundedCornerShape((24 * scale).dp),
    extraLarge = RoundedCornerShape((28 * scale).dp)
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
        surfaceTint = tone(h, s.coerceAtMost(0.72f), 0.78f)
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
        surfaceTint = tone(h, s, 0.4f)
    )
}

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
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val seed = parseSeedColor(accentColor)
    val colorScheme = when {
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

    CompositionLocalProvider(LocalCornerScale provides cornerScale) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PocketTypography,
            shapes = appShapes(cornerScale),
            content = content
        )
    }
}

/**
 * 从全局 SettingsRepository 读 darkTheme / dynamicColor / accentColor / cornerScale 自动配置。
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
        content = content
    )
}
