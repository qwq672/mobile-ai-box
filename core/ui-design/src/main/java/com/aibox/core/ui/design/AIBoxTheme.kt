package com.aibox.core.ui.design

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * AIBox 主题：Material 3 + 动态配色 (Material You)。
 *
 * - Android 12+ 跟随系统壁纸取色
 * - Android 10/11 回退到 AIBox 品牌默认配色
 */
@Composable
public fun AIBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> AIBoxColorScheme.Dark
        else -> AIBoxColorScheme.Light
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

public object AIBoxColorScheme {
    public val Light = lightColorScheme(
        primary = Color(0xFF00696D),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF6FF7FB),
        onPrimaryContainer = Color(0xFF002021),
        secondary = Color(0xFF4A6364),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFCCE8E8),
        onSecondaryContainer = Color(0xFF051F1F),
        tertiary = Color(0xFF4B6074),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFD3E4FF),
        onTertiaryContainer = Color(0xFF041C2E),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        background = Color(0xFFFAFDFD),
        onBackground = Color(0xFF191C1C),
        surface = Color(0xFFFAFDFD),
        onSurface = Color(0xFF191C1C),
        surfaceVariant = Color(0xFFDAE4E4),
        onSurfaceVariant = Color(0xFF3F4949),
        outline = Color(0xFF6F7979),
    )

    public val Dark = darkColorScheme(
        primary = Color(0xFF4DDADE),
        onPrimary = Color(0xFF003739),
        primaryContainer = Color(0xFF004F53),
        onPrimaryContainer = Color(0xFF6FF7FB),
        secondary = Color(0xFFB0CCCB),
        onSecondary = Color(0xFF1B3534),
        secondaryContainer = Color(0xFF324B4B),
        onSecondaryContainer = Color(0xFFCCE8E8),
        tertiary = Color(0xFFB5C8E6),
        onTertiary = Color(0xFF1A3045),
        tertiaryContainer = Color(0xFF31475C),
        onTertiaryContainer = Color(0xFFD3E4FF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        background = Color(0xFF191C1C),
        onBackground = Color(0xFFE0E3E2),
        surface = Color(0xFF191C1C),
        onSurface = Color(0xFFE0E3E2),
        surfaceVariant = Color(0xFF3F4949),
        onSurfaceVariant = Color(0xFFBEC9C8),
        outline = Color(0xFF899392),
    )
}
