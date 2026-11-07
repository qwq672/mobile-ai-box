package com.pocketllm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Family = FontFamily.Default

val PocketTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp
    ),
    displaySmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 32.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp
    )
)
