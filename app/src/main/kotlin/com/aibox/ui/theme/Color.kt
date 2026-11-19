package com.aibox.ui.theme

import androidx.compose.ui.graphics.Color

// 主色板（Material You 会基于此动态调色）
val SeedBlue   = Color(0xFF3B6FE6)
val SeedPurple = Color(0xFF7A4DD0)
val SeedGreen  = Color(0xFF1F8A4C)

// 浅色
val md_light_primary        = Color(0xFF2A5FE0)
val md_light_onPrimary      = Color(0xFFFFFFFF)
val md_light_secondary      = Color(0xFF5A6376)
val md_light_onSecondary    = Color(0xFFFFFFFF)
val md_light_tertiary       = Color(0xFF7C4DCB)
val md_light_background     = Color(0xFFF7F8FB)
val md_light_surface        = Color(0xFFFFFFFF)
val md_light_onSurface      = Color(0xFF1A1B1F)
val md_light_outline        = Color(0xFF74777F)
val md_light_surfaceVariant = Color(0xFFE1E2EC)

// 深色
val md_dark_primary         = Color(0xFFB6C4FF)
val md_dark_onPrimary       = Color(0xFF142E78)
val md_dark_secondary       = Color(0xFFC3C6DC)
val md_dark_onSecondary     = Color(0xFF2C2F40)
val md_dark_tertiary        = Color(0xFFD6BBFF)
val md_dark_background      = Color(0xFF0E0F14)
val md_dark_surface         = Color(0xFF15161C)
val md_dark_onSurface       = Color(0xFFE5E6EE)
val md_dark_outline         = Color(0xFF8E909A)
val md_dark_surfaceVariant  = Color(0xFF22232C)

// ============================================================================
// 中性 / 黑白主题灰阶（R=G=B，无任何色相倾向）
// ----------------------------------------------------------------------------
// 供 Theme.kt 的 neutralColorScheme() 使用：背景/表面/容器/描边全部走这套纯灰阶，
// 只有 primary/secondary/tertiary 点缀色保留来自强调色的彩色。
// 近黑用 #121212 起步而非 #000000，避免纯黑与卡片灰阶之间的"黑阶断层"。
// 语义色（error=红 / success / warning）不在此列，由 base scheme 原样保留。
// ============================================================================

// 中性·暗色（近黑）
val neutral_dark_background            = Color(0xFF121212)
val neutral_dark_surface               = Color(0xFF121212)
val neutral_dark_surfaceVariant        = Color(0xFF1E1E1E)
val neutral_dark_surfaceContainerLow   = Color(0xFF1A1A1A)
val neutral_dark_surfaceContainer      = Color(0xFF202020)
val neutral_dark_surfaceContainerHigh  = Color(0xFF2A2A2A)
val neutral_dark_surfaceContainerHighest = Color(0xFF353535)
val neutral_dark_onSurface             = Color(0xFFEDEDED)
val neutral_dark_onSurfaceVariant      = Color(0xFFB4B4B4)
val neutral_dark_outline               = Color(0xFF7A7A7A)
val neutral_dark_outlineVariant        = Color(0xFF454545)
val neutral_dark_inverseSurface        = Color(0xFFEDEDED)
val neutral_dark_inverseOnSurface      = Color(0xFF1A1A1A)

// 中性·亮色（近白）
val neutral_light_background           = Color(0xFFFFFFFF)
val neutral_light_surface              = Color(0xFFFFFFFF)
val neutral_light_surfaceVariant       = Color(0xFFF0F0F0)
val neutral_light_surfaceContainerLow  = Color(0xFFF7F7F7)
val neutral_light_surfaceContainer     = Color(0xFFF0F0F0)
val neutral_light_surfaceContainerHigh = Color(0xFFE8E8E8)
val neutral_light_surfaceContainerHighest = Color(0xFFE0E0E0)
val neutral_light_onSurface            = Color(0xFF1A1A1A)
val neutral_light_onSurfaceVariant     = Color(0xFF4A4A4A)
val neutral_light_outline              = Color(0xFF757575)
val neutral_light_outlineVariant       = Color(0xFFC7C7C7)
val neutral_light_inverseSurface       = Color(0xFF2A2A2A)
val neutral_light_inverseOnSurface     = Color(0xFFF5F5F5)
