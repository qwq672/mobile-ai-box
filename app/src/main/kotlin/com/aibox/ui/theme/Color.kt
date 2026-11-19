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

// ============================================================================
// 中性主题·强调色灰阶（R=G=B）—— 用户明确要求「黑白主题不使用强调色」
// ----------------------------------------------------------------------------
// primary 是最高对比的灰（暗色近白 / 亮色近黑），保证 FilledButton、选中态、
// FAB 等仍是清晰可辨的黑白，而不是蓝色。secondary / tertiary 依次降对比，
// 形成「主-次-三」三级灰阶层次。容器底色沿用 neutral_* surfaceContainer，
// on*Container 走灰阶文字，彻底移除任何色相。
// ============================================================================

// 中性·暗色 强调灰阶
val neutral_dark_primary               = Color(0xFFEDEDED)
val neutral_dark_onPrimary             = Color(0xFF1A1A1A)
val neutral_dark_secondary             = Color(0xFFC4C4C4)
val neutral_dark_onSecondary           = Color(0xFF1A1A1A)
val neutral_dark_tertiary              = Color(0xFF9E9E9E)
val neutral_dark_onTertiary            = Color(0xFF1A1A1A)
val neutral_dark_inversePrimary        = Color(0xFF6A6A6A)

// 中性·亮色 强调灰阶
val neutral_light_primary              = Color(0xFF1A1A1A)
val neutral_light_onPrimary            = Color(0xFFFFFFFF)
val neutral_light_secondary            = Color(0xFF4A4A4A)
val neutral_light_onSecondary          = Color(0xFFFFFFFF)
val neutral_light_tertiary             = Color(0xFF6E6E6E)
val neutral_light_onTertiary           = Color(0xFFFFFFFF)
val neutral_light_inversePrimary       = Color(0xFFBDBDBD)

// ============================================================================
// 中性主题·语义点缀色（少量彩色）—— 黑白之外的「功能色」
// ----------------------------------------------------------------------------
// 中性主题只保留必要的彩色点缀：成功=绿、警告=黄/橙、危险=红。
// 停止按钮本身走 error（红），已由 base scheme 保留；这里补足成功/警告色，
// 供 DesignTokens 的 stateSuccess / stateWarning 在中性主题下使用，避免
// 因 primary 变灰而把「完成」「警告」也一并灰掉。
// 这些色不用作大面积背景，只用于图标 / 徽章 / 状态文字等小面积点缀。
// ============================================================================

// 中性·暗色 点缀色（在近黑底上提亮，保证对比度）
val neutral_dark_success               = Color(0xFF6CCB8C)
val neutral_dark_warning               = Color(0xFFF5C26B)
val neutral_dark_danger                = Color(0xFFFF8A80)

// 中性·亮色 点缀色（在近白底上压深，保证对比度）
val neutral_light_success              = Color(0xFF1B7F45)
val neutral_light_warning              = Color(0xFF9A6700)
val neutral_light_danger               = Color(0xFFBA1A1A)
