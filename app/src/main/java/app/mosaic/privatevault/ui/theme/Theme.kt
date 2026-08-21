package app.mosaic.privatevault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Very dark grey and off-white. No accent colour loud enough to be recognised
 * across a room, no brand cue, nothing that reads as "messaging app" over
 * someone's shoulder.
 */
private val Ink = Color(0xFF0E0E10)
private val Surface1 = Color(0xFF161619)
private val Surface2 = Color(0xFF1F1F23)
private val Outline = Color(0xFF33333A)
private val Bone = Color(0xFFEDEAE4)
private val BoneDim = Color(0xFF9A9891)
private val Accent = Color(0xFF6E7B8B)

private val PaperInk = Color(0xFF1A1A1D)
private val Paper = Color(0xFFF4F2ED)
private val Paper2 = Color(0xFFE8E5DE)

private val MosaicDark = darkColorScheme(
    primary = Accent,
    onPrimary = Bone,
    primaryContainer = Surface2,
    onPrimaryContainer = Bone,
    secondary = BoneDim,
    onSecondary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = Surface1,
    onSurface = Bone,
    surfaceVariant = Surface2,
    onSurfaceVariant = BoneDim,
    outline = Outline,
    outlineVariant = Outline,
    error = Color(0xFFB4776F),
    onError = Bone,
)

private val MosaicLight = lightColorScheme(
    primary = Accent,
    onPrimary = Paper,
    primaryContainer = Paper2,
    onPrimaryContainer = PaperInk,
    secondary = Color(0xFF5C5A55),
    onSecondary = Paper,
    background = Paper,
    onBackground = PaperInk,
    surface = Paper,
    onSurface = PaperInk,
    surfaceVariant = Paper2,
    onSurfaceVariant = Color(0xFF5C5A55),
    outline = Color(0xFFC9C5BC),
    outlineVariant = Color(0xFFD9D5CC),
    error = Color(0xFF8E4B42),
    onError = Paper,
)

private val MosaicShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val MosaicTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 0.4.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp),
)

@Composable
fun MosaicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic colour is deliberately not used: the wallpaper-derived palette
    // would make Mosaic look different on every phone and occasionally loud.
    MaterialTheme(
        colorScheme = if (darkTheme) MosaicDark else MosaicLight,
        shapes = MosaicShapes,
        typography = MosaicTypography,
        content = content,
    )
}
