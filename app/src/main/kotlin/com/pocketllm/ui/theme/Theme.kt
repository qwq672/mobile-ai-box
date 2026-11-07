package com.pocketllm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import android.content.ContextWrapper
import androidx.compose.ui.graphics.Color
import com.pocketllm.PocketLLMApp
import com.pocketllm.data.repo.SettingsRepository

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

private val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PocketLLMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PocketTypography,
        shapes = PocketShapes,
        content = content
    )
}

/**
 * 从全局 SettingsRepository 读 darkTheme / dynamicColor 自动配置。
 * 用在 MainActivity 的根 composable。
 */
@Composable
fun PocketLLMThemeFromSettings(content: @Composable () -> Unit) {
    val app = PocketLLMApp.instance
    val settings: SettingsRepository = app.container.settingsRepository
    val darkThemeMode by settings.darkTheme.collectAsState(initial = "system")
    val dynamicColor by settings.dynamicColor.collectAsState(initial = true)

    val isDark = when (darkThemeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    PocketLLMTheme(darkTheme = isDark, dynamicColor = dynamicColor, content = content)
}
