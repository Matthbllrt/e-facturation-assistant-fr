package com.radardeal.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * RadarDeal's palette.
 *
 * The app is dark by design, not "dark mode" — a single, committed look, the way a trading or
 * sneaker app looks. There is no light variant to keep consistent, and [isSystemInDarkTheme] is
 * deliberately ignored.
 */
object RadarColors {
    val Background = Color(0xFF070B10)
    val Surface = Color(0xFF0D151D)
    val SurfaceElevated = Color(0xFF111A23)
    val SurfacePressed = Color(0xFF16212C)

    val Accent = Color(0xFF00C7A5)
    val AccentSoft = Color(0x2900C7A5)
    val AccentMuted = Color(0xFF0C8672)

    val TextPrimary = Color(0xFFF5F8FB)
    val TextSecondary = Color(0xFF8492A0)
    val TextTertiary = Color(0xFF5A6875)

    val Outline = Color(0xFF1C2833)
    val OutlineStrong = Color(0xFF27394A)

    val Danger = Color(0xFFFF5B6E)
    val DangerSoft = Color(0x24FF5B6E)
    val Warning = Color(0xFFFFB020)
    val WarningSoft = Color(0x24FFB020)
    val PriceDrop = Color(0xFF4DA3FF)
    val PriceDropSoft = Color(0x244DA3FF)
    val Live = Color(0xFF00E0A8)
}

/** Spacing scale. Generous by default — the layout leans on whitespace rather than dividers. */
@Suppress("PropertyName")
class RadarSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val screen: Dp = 20.dp
}

val LocalSpacing = staticCompositionLocalOf { RadarSpacing() }

private val RadarColorScheme = darkColorScheme(
    primary = RadarColors.Accent,
    onPrimary = Color(0xFF00251E),
    primaryContainer = RadarColors.AccentMuted,
    onPrimaryContainer = RadarColors.TextPrimary,
    secondary = RadarColors.Accent,
    onSecondary = Color(0xFF00251E),
    background = RadarColors.Background,
    onBackground = RadarColors.TextPrimary,
    surface = RadarColors.Surface,
    onSurface = RadarColors.TextPrimary,
    surfaceVariant = RadarColors.SurfaceElevated,
    onSurfaceVariant = RadarColors.TextSecondary,
    outline = RadarColors.Outline,
    outlineVariant = RadarColors.Outline,
    error = RadarColors.Danger,
    onError = Color(0xFF2A0007),
    scrim = Color(0xCC000000),
)

private val lineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * Typography built on the system font, with deliberately strong weight contrast: near-black
 * display numbers against light body copy is what makes the hierarchy read instantly.
 */
private val RadarTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1.2).sp,
        lineHeightStyle = lineHeightStyle,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.9).sp,
        lineHeightStyle = lineHeightStyle,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp,
        lineHeightStyle = lineHeightStyle,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.4).sp,
        lineHeightStyle = lineHeightStyle,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.2).sp,
        lineHeightStyle = lineHeightStyle,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        lineHeightStyle = lineHeightStyle,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        lineHeightStyle = lineHeightStyle,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        lineHeightStyle = lineHeightStyle,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = lineHeightStyle,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.9.sp,
        lineHeightStyle = lineHeightStyle,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = lineHeightStyle,
    ),
)

@Composable
fun RadarDealTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSpacing provides RadarSpacing()) {
        MaterialTheme(
            colorScheme = RadarColorScheme,
            typography = RadarTypography,
            shapes = RadarShapes,
            content = content,
        )
    }
}
