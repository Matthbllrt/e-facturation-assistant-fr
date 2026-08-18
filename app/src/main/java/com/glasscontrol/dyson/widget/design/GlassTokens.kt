package com.glasscontrol.dyson.widget.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.unit.ColorProvider

/**
 * The single source of truth for the widget's look.
 *
 * Every radius, alpha and colour used on the home screen comes from here, so the
 * two widget sizes and the in-app preview cannot drift apart, and a change to the
 * glass recipe is one edit rather than twenty.
 */
object GlassTokens {

    // Geometry
    val RadiusLarge = 32.dp
    val RadiusSmall = 18.dp
    val RadiusPill = 22.dp

    /** Launchers already inset widgets; padding on top of that wastes real estate. */
    val SurfacePaddingH = 14.dp
    val SurfacePaddingV = 12.dp

    /** Below this, a touch target stops being reliably hittable. */
    val MinTouchTarget = 44.dp

    // Colour — smoked glass, white text, cyan reserved for what matters
    val AccentCyan = Color(0xFF5AD7F0)
    val AccentCyanSoft = Color(0x335AD7F0)

    val TextPrimary = Color(0xFFF6FAFD)
    val TextSecondary = Color(0xB3C6D6E3)
    val TextTertiary = Color(0x8AAEC0D0)

    val StatusOnline = Color(0xFF5AD7F0)
    val StatusOffline = Color(0xFFFF8A80)
    val StatusUnknown = Color(0x99AEC0D0)

    val TextPrimaryLight = Color(0xFF0E151C)
    val TextSecondaryLight = Color(0xB3243240)
    val AccentCyanLight = Color(0xFF0B7185)
    val StatusOfflineLight = Color(0xFFB3372C)

    // Providers, so composables do not rebuild them on every recomposition
    val Primary = ColorProvider(TextPrimary)
    val Secondary = ColorProvider(TextSecondary)
    val Tertiary = ColorProvider(TextTertiary)
    val Accent = ColorProvider(AccentCyan)
    val Online = ColorProvider(StatusOnline)
    val Offline = ColorProvider(StatusOffline)
    val Unknown = ColorProvider(StatusUnknown)

    val PrimaryLight = ColorProvider(TextPrimaryLight)
    val SecondaryLight = ColorProvider(TextSecondaryLight)
    val AccentLight = ColorProvider(AccentCyanLight)
    val OfflineLight = ColorProvider(StatusOfflineLight)
}
