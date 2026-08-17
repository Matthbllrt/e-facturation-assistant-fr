package com.glasscontrol.dyson.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import com.glasscontrol.dyson.R
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetTheme

/**
 * Resolved look for one widget instance.
 *
 * A widget cannot read the wallpaper, so "glass" is simulated with layered
 * translucency; the only real choice is whether the panel leans light or dark,
 * and how much of the wallpaper shows through.
 */
data class ResolvedWidgetTheme(
    val dark: Boolean,
    val panelRes: Int,
    val chipRes: Int,
    val chipActiveRes: Int,
    val chipWarmRes: Int,
    val pillRes: Int,
) {
    val primaryText: ColorProvider
        get() = ColorProvider(if (dark) Color(0xFFF2F7FB) else Color(0xFF10161D))

    val secondaryText: ColorProvider
        get() = ColorProvider(if (dark) Color(0xB3C7D6E2) else Color(0x9E2C3742))

    val accentText: ColorProvider
        get() = ColorProvider(if (dark) Color(0xFF7FE3F5) else Color(0xFF0E7C93))

    val warmText: ColorProvider
        get() = ColorProvider(if (dark) Color(0xFFFFB070) else Color(0xFFB2560A))

    val offlineText: ColorProvider
        get() = ColorProvider(if (dark) Color(0xFFFF9B9B) else Color(0xFFB3261E))

    val iconTint: ColorProvider
        get() = ColorProvider(if (dark) Color(0xFFE6EFF6) else Color(0xFF1B2530))

    val iconTintActive: ColorProvider
        get() = ColorProvider(if (dark) Color(0xFF9DEBFA) else Color(0xFF0B6B80))

    val iconTintWarm: ColorProvider
        get() = ColorProvider(if (dark) Color(0xFFFFC08A) else Color(0xFF9A4A05))

    companion object {

        private val DARK_PANELS = intArrayOf(
            R.drawable.glass_panel_dark_0,
            R.drawable.glass_panel_dark_1,
            R.drawable.glass_panel_dark_2,
            R.drawable.glass_panel_dark_3,
            R.drawable.glass_panel_dark_4,
        )

        private val LIGHT_PANELS = intArrayOf(
            R.drawable.glass_panel_light_0,
            R.drawable.glass_panel_light_1,
            R.drawable.glass_panel_light_2,
            R.drawable.glass_panel_light_3,
            R.drawable.glass_panel_light_4,
        )

        fun resolve(context: Context, config: WidgetConfig): ResolvedWidgetTheme {
            val dark = when (config.theme) {
                WidgetTheme.DARK -> true
                WidgetTheme.LIGHT -> false
                WidgetTheme.AUTO -> isSystemDark(context)
            }
            // The slider is continuous but the panel comes from a fixed set of
            // drawables, so it maps onto the nearest bucket.
            val bucket = (config.glassOpacity.coerceIn(0f, 1f) * (DARK_PANELS.size - 1))
                .toInt()
                .coerceIn(0, DARK_PANELS.size - 1)

            return ResolvedWidgetTheme(
                dark = dark,
                panelRes = if (dark) DARK_PANELS[bucket] else LIGHT_PANELS[bucket],
                chipRes = if (dark) R.drawable.chip_dark else R.drawable.chip_light,
                chipActiveRes = if (dark) R.drawable.chip_dark_active else R.drawable.chip_light_active,
                chipWarmRes = if (dark) R.drawable.chip_dark_warm else R.drawable.chip_light_warm,
                pillRes = if (dark) R.drawable.pill_dark else R.drawable.pill_light,
            )
        }

        private fun isSystemDark(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}
