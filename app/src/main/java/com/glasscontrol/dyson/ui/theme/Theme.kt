package com.glasscontrol.dyson.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The glass palette. Deliberately narrow: near-neutral surfaces, one accent. */
data class GlassColors(
    val dark: Boolean,
    val background: Brush,
    val surface: Color,
    val surfaceStrong: Color,
    val border: Color,
    val borderStrong: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val warm: Color,
    val danger: Color,
) {
    val highlight: Brush
        get() = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.16f else 0.55f),
            1f to Color.White.copy(alpha = 0f),
        )
}

private val DarkGlass = GlassColors(
    dark = true,
    background = Brush.linearGradient(
        0f to Color(0xFF0B0F14),
        0.45f to Color(0xFF111823),
        1f to Color(0xFF080B10),
    ),
    surface = Color.White.copy(alpha = 0.07f),
    surfaceStrong = Color.White.copy(alpha = 0.12f),
    border = Color.White.copy(alpha = 0.10f),
    borderStrong = Color.White.copy(alpha = 0.22f),
    onSurface = Color(0xFFF1F6FA),
    onSurfaceMuted = Color(0xFF9FB0BF),
    accent = Color(0xFF6FDCF2),
    accentSoft = Color(0xFF6FDCF2).copy(alpha = 0.16f),
    warm = Color(0xFFFFA95E),
    danger = Color(0xFFFF8A80),
)

private val LightGlass = GlassColors(
    dark = false,
    background = Brush.linearGradient(
        0f to Color(0xFFF4F7FA),
        0.5f to Color(0xFFE8EEF5),
        1f to Color(0xFFDDE5EE),
    ),
    surface = Color.White.copy(alpha = 0.72f),
    surfaceStrong = Color.White.copy(alpha = 0.88f),
    border = Color.White.copy(alpha = 0.85f),
    borderStrong = Color(0xFF9FB3C4).copy(alpha = 0.45f),
    onSurface = Color(0xFF101820),
    onSurfaceMuted = Color(0xFF5A6B7A),
    accent = Color(0xFF0E7C93),
    accentSoft = Color(0xFF0E7C93).copy(alpha = 0.12f),
    warm = Color(0xFFB2560A),
    danger = Color(0xFFB3261E),
)

val LocalGlassColors = staticCompositionLocalOf { DarkGlass }

/** App theme preference, mirrored into the widget's own theme setting. */
enum class AppTheme { LIGHT, DARK, SYSTEM }

private val GlassTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun DysonGlassTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (appTheme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val glass = if (dark) DarkGlass else LightGlass

    val scheme = if (dark) {
        darkColorScheme(
            primary = glass.accent,
            background = Color(0xFF0B0F14),
            surface = Color(0xFF111823),
            onBackground = glass.onSurface,
            onSurface = glass.onSurface,
        )
    } else {
        lightColorScheme(
            primary = glass.accent,
            background = Color(0xFFF4F7FA),
            surface = Color.White,
            onBackground = glass.onSurface,
            onSurface = glass.onSurface,
        )
    }

    CompositionLocalProvider(LocalGlassColors provides glass) {
        MaterialTheme(colorScheme = scheme, typography = GlassTypography, content = content)
    }
}
