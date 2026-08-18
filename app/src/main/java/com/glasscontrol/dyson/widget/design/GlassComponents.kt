package com.glasscontrol.dyson.widget.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.glasscontrol.dyson.R

/**
 * The drawables and colours for one theme, resolved once per render.
 *
 * Light and dark are two palettes over the same recipe, so the widget keeps its
 * identity on a pale wallpaper without a second layout.
 */
data class GlassPalette(
    val dark: Boolean,
    val surface: Int,
    val button: Int,
    val buttonActive: Int,
    val powerOn: Int,
    val vertical: Int,
    val flat: Int,
    val primary: ColorProvider,
    val secondary: ColorProvider,
    val accent: ColorProvider,
    val offline: ColorProvider,
) {
    companion object {
        fun of(dark: Boolean): GlassPalette = if (dark) {
            GlassPalette(
                dark = true,
                surface = R.drawable.glass_surface_dark,
                button = R.drawable.glass_btn,
                buttonActive = R.drawable.glass_btn_active,
                powerOn = R.drawable.glass_btn_power_on,
                vertical = R.drawable.glass_btn_vertical,
                flat = R.drawable.glass_btn_flat,
                primary = GlassTokens.Primary,
                secondary = GlassTokens.Secondary,
                accent = GlassTokens.Accent,
                offline = GlassTokens.Offline,
            )
        } else {
            GlassPalette(
                dark = false,
                surface = R.drawable.glass_surface_light,
                button = R.drawable.glass_btn_light,
                buttonActive = R.drawable.glass_btn_active_light,
                powerOn = R.drawable.glass_btn_power_on_light,
                vertical = R.drawable.glass_btn_vertical_light,
                flat = R.drawable.glass_btn_flat_light,
                primary = GlassTokens.PrimaryLight,
                secondary = GlassTokens.SecondaryLight,
                accent = GlassTokens.AccentLight,
                offline = GlassTokens.OfflineLight,
            )
        }
    }
}

/** The translucent slab every widget sits on. */
@Composable
fun GlassWidgetSurface(
    palette: GlassPalette,
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ImageProvider(palette.surface))
            .padding(
                horizontal = GlassTokens.SurfacePaddingH,
                vertical = GlassTokens.SurfacePaddingV,
            ),
    ) {
        content()
    }
}

/**
 * A pill-shaped control carrying an icon.
 *
 * The background is a state-list drawable, which is the one press feedback an
 * AppWidget can give honestly — no animation faking required.
 */
@Composable
fun GlassControlButton(
    iconRes: Int,
    contentDescription: String,
    active: Boolean,
    palette: GlassPalette,
    onClick: Action,
    height: Dp,
    iconSize: Dp,
    modifier: GlanceModifier = GlanceModifier,
    emphasised: Boolean = false,
) {
    val background = when {
        active && emphasised -> palette.powerOn
        active -> palette.buttonActive
        else -> palette.button
    }
    Box(
        modifier = modifier
            .height(height)
            .background(ImageProvider(background))
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(if (active) palette.accent else palette.primary),
            modifier = GlanceModifier.size(iconSize),
        )
    }
}

/** A pill-shaped control carrying a word, such as "Auto". */
@Composable
fun GlassLabelButton(
    label: String,
    contentDescription: String,
    active: Boolean,
    palette: GlassPalette,
    onClick: Action,
    height: Dp,
    fontSize: TextUnit,
    modifier: GlanceModifier = GlanceModifier,
) {
    Box(
        modifier = modifier
            .height(height)
            .background(ImageProvider(if (active) palette.buttonActive else palette.button))
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = if (active) palette.accent else palette.primary,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** The tall rounded control used for speed − and +. */
@Composable
fun GlassVerticalButton(
    iconRes: Int,
    contentDescription: String,
    palette: GlassPalette,
    onClick: Action,
    width: Dp,
    height: Dp,
    iconSize: Dp,
    enabled: Boolean = true,
) {
    val base = GlanceModifier
        .width(width)
        .height(height)
        .background(ImageProvider(palette.vertical))

    Box(
        modifier = if (enabled) base.clickable(onClick) else base,
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(
                if (enabled) palette.primary else palette.secondary
            ),
            modifier = GlanceModifier.size(iconSize),
        )
    }
}

/**
 * A borderless control, for the refresh affordance.
 *
 * Visually it is just the icon, but the touch target is deliberately far larger
 * than the glyph so it stays hittable on a home screen.
 */
@Composable
fun GlassFlatButton(
    iconRes: Int,
    contentDescription: String,
    palette: GlassPalette,
    onClick: Action,
    size: Dp,
    iconSize: Dp,
    tint: ColorProvider = palette.secondary,
) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .background(ImageProvider(palette.flat))
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(iconSize),
        )
    }
}

/**
 * "TP07 • Online" with a coloured dot.
 *
 * Offline is stated once, quietly, rather than shouted with an error panel: the
 * controls stay usable and the user simply sees the machine is not answering.
 */
@Composable
fun WidgetStatusIndicator(
    model: String,
    statusLabel: String,
    online: Boolean,
    degraded: Boolean,
    palette: GlassPalette,
    fontSize: TextUnit,
) {
    val statusColor = when {
        online -> palette.accent
        degraded -> palette.offline
        else -> GlassTokens.Unknown
    }
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        if (model.isNotEmpty()) {
            Text(
                text = model,
                style = TextStyle(
                    color = palette.secondary,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Text(
                text = "  •  ",
                style = TextStyle(color = GlassTokens.Unknown, fontSize = fontSize),
            )
        }
        Text(
            text = statusLabel,
            style = TextStyle(color = statusColor, fontSize = fontSize, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

/** The wordmark, tracked out with thin spaces since Glance has no letter spacing. */
@Composable
fun WidgetWordmark(palette: GlassPalette, fontSize: TextUnit) {
    Text(
        text = "D Y S O N",
        style = TextStyle(
            color = palette.primary,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

/** Fixed-width gap helper, so layouts read as a sequence of spacings. */
@Composable
fun HGap(width: Dp) = Spacer(GlanceModifier.width(width))

@Composable
fun VGap(height: Dp) = Spacer(GlanceModifier.height(height))

/** Full-width gap that pushes what follows to the bottom of a column. */
@Composable
fun FlexibleGap(modifier: GlanceModifier) = Spacer(modifier.fillMaxWidth())
