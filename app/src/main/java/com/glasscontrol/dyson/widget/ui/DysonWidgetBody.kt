package com.glasscontrol.dyson.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionParametersOf
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.glasscontrol.dyson.R
import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.ui.MainActivity
import com.glasscontrol.dyson.ui.art.DysonRenderCache
import com.glasscontrol.dyson.ui.art.DysonVisual
import com.glasscontrol.dyson.widget.DysonCommandAction
import com.glasscontrol.dyson.widget.WidgetCommands
import com.glasscontrol.dyson.widget.WidgetStatus
import com.glasscontrol.dyson.widget.WidgetUiState
import com.glasscontrol.dyson.widget.design.GlassControlButton
import com.glasscontrol.dyson.widget.design.GlassFlatButton
import com.glasscontrol.dyson.widget.design.GlassLabelButton
import com.glasscontrol.dyson.widget.design.GlassPalette
import com.glasscontrol.dyson.widget.design.GlassVerticalButton
import com.glasscontrol.dyson.widget.design.GlassWidgetSurface
import com.glasscontrol.dyson.widget.design.HGap
import com.glasscontrol.dyson.widget.design.VGap
import com.glasscontrol.dyson.widget.design.WidgetStatusIndicator
import com.glasscontrol.dyson.widget.design.WidgetWordmark
import kotlin.math.roundToInt

/**
 * The sizes that separate the compact widget from the large one.
 *
 * Both sizes run the exact same layout: only these numbers change. That is what
 * keeps the two visually identical in character, and means a layout fix lands in
 * both at once.
 */
data class WidgetMetrics(
    val dysonWidth: Dp,
    val dysonHeight: Dp,
    val columnGap: Dp,
    val rowGap: Dp,
    val wordmarkSize: TextUnit,
    val statusSize: TextUnit,
    val refreshTouch: Dp,
    val refreshIcon: Dp,
    val speedSize: TextUnit,
    val stepWidth: Dp,
    val stepHeight: Dp,
    val stepIcon: Dp,
    val controlHeight: Dp,
    val controlIcon: Dp,
    val controlLabel: TextUnit,
) {
    companion object {
        /** Roughly a 4x2 Samsung cell. */
        val Compact = WidgetMetrics(
            dysonWidth = 56.dp,
            dysonHeight = 92.dp,
            columnGap = 10.dp,
            rowGap = 6.dp,
            wordmarkSize = 11.sp,
            statusSize = 10.sp,
            refreshTouch = 32.dp,
            refreshIcon = 15.dp,
            speedSize = 24.sp,
            stepWidth = 30.dp,
            stepHeight = 34.dp,
            stepIcon = 16.dp,
            controlHeight = 34.dp,
            controlIcon = 17.dp,
            controlLabel = 12.sp,
        )

        /** Roughly a 4x3 Samsung cell: spectacular without eating the screen. */
        val Large = WidgetMetrics(
            dysonWidth = 90.dp,
            dysonHeight = 148.dp,
            columnGap = 14.dp,
            rowGap = 10.dp,
            wordmarkSize = 14.sp,
            statusSize = 12.sp,
            refreshTouch = 38.dp,
            refreshIcon = 18.dp,
            speedSize = 34.sp,
            stepWidth = 36.dp,
            stepHeight = 48.dp,
            stepIcon = 19.dp,
            controlHeight = 46.dp,
            controlIcon = 21.dp,
            controlLabel = 14.sp,
        )
    }
}

@Composable
fun DysonWidgetBody(ui: WidgetUiState, palette: GlassPalette, metrics: WidgetMetrics) {
    GlassWidgetSurface(palette) {
        if (!ui.configured) {
            NotConfigured(palette, metrics)
            return@GlassWidgetSurface
        }

        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            DysonDeviceRender(ui, palette, metrics)
            HGap(metrics.columnGap)

            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                HeaderRow(ui, palette, metrics)
                Spacer(GlanceModifier.defaultWeight())
                if (ui.capabilities.fanSpeed) {
                    SpeedRow(ui, palette, metrics)
                    VGap(metrics.rowGap)
                }
                ControlRow(ui, palette, metrics)
            }
        }
    }
}

/** Wordmark, model badge and the refresh affordance. */
@Composable
private fun HeaderRow(ui: WidgetUiState, palette: GlassPalette, metrics: WidgetMetrics) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            WidgetWordmark(palette, metrics.wordmarkSize)
            WidgetStatusIndicator(
                model = ui.model,
                statusLabel = ui.statusLabel,
                online = ui.isOnline,
                degraded = ui.status == WidgetStatus.OFFLINE || ui.status == WidgetStatus.ERROR,
                palette = palette,
                fontSize = metrics.statusSize,
            )
        }
        GlassFlatButton(
            iconRes = R.drawable.ic_refresh,
            contentDescription = "Actualiser l'état du Dyson",
            palette = palette,
            onClick = actionRunCallback<DysonCommandAction>(
                actionParametersOf(WidgetCommands.KEY to WidgetCommands.REFRESH)
            ),
            size = metrics.refreshTouch,
            iconSize = metrics.refreshIcon,
            tint = if (ui.status == WidgetStatus.REFRESHING) palette.accent else palette.secondary,
        )
    }
}

/** − 06 + */
@Composable
private fun SpeedRow(ui: WidgetUiState, palette: GlassPalette, metrics: WidgetMetrics) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        GlassVerticalButton(
            iconRes = R.drawable.ic_minus,
            contentDescription = "Diminuer la vitesse",
            palette = palette,
            onClick = actionRunCallback<DysonCommandAction>(
                actionParametersOf(WidgetCommands.KEY to WidgetCommands.SPEED_DOWN)
            ),
            width = metrics.stepWidth,
            height = metrics.stepHeight,
            iconSize = metrics.stepIcon,
        )
        Box(
            modifier = GlanceModifier.defaultWeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ui.speedLabel,
                style = TextStyle(
                    color = palette.primary,
                    fontSize = metrics.speedSize,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
        GlassVerticalButton(
            iconRes = R.drawable.ic_plus,
            contentDescription = "Augmenter la vitesse",
            palette = palette,
            onClick = actionRunCallback<DysonCommandAction>(
                actionParametersOf(WidgetCommands.KEY to WidgetCommands.SPEED_UP)
            ),
            width = metrics.stepWidth,
            height = metrics.stepHeight,
            iconSize = metrics.stepIcon,
        )
    }
}

/** Power · Auto · Oscillation, each shown only if the machine supports it. */
@Composable
private fun ControlRow(ui: WidgetUiState, palette: GlassPalette, metrics: WidgetMetrics) {
    val capabilities: DysonCapabilities = ui.capabilities

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (capabilities.power) {
            GlassControlButton(
                iconRes = R.drawable.ic_power,
                contentDescription = if (ui.power) "Éteindre le Dyson" else "Allumer le Dyson",
                active = ui.power,
                palette = palette,
                onClick = actionRunCallback<DysonCommandAction>(
                    actionParametersOf(WidgetCommands.KEY to WidgetCommands.TOGGLE_POWER)
                ),
                height = metrics.controlHeight,
                iconSize = metrics.controlIcon,
                modifier = GlanceModifier.defaultWeight(),
                emphasised = true,
            )
        }
        if (capabilities.autoMode) {
            HGap(metrics.rowGap)
            GlassLabelButton(
                label = "Auto",
                contentDescription = if (ui.autoMode) "Désactiver le mode auto" else "Activer le mode auto",
                active = ui.autoMode,
                palette = palette,
                onClick = actionRunCallback<DysonCommandAction>(
                    actionParametersOf(WidgetCommands.KEY to WidgetCommands.TOGGLE_AUTO)
                ),
                height = metrics.controlHeight,
                fontSize = metrics.controlLabel,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
        if (capabilities.oscillation) {
            HGap(metrics.rowGap)
            GlassControlButton(
                iconRes = R.drawable.ic_oscillation,
                contentDescription = if (ui.oscillation) "Arrêter l'oscillation" else "Activer l'oscillation",
                active = ui.oscillation,
                palette = palette,
                onClick = actionRunCallback<DysonCommandAction>(
                    actionParametersOf(WidgetCommands.KEY to WidgetCommands.TOGGLE_OSCILLATION)
                ),
                height = metrics.controlHeight,
                iconSize = metrics.controlIcon,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }
}

/**
 * The machine itself.
 *
 * Rasterised through a cache, because a widget repaints on every state change
 * and the picture only has a handful of distinct forms.
 */
@Composable
private fun DysonDeviceRender(ui: WidgetUiState, palette: GlassPalette, metrics: WidgetMetrics) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density

    val visual = DysonVisual(
        on = ui.power && ui.isOnline,
        speedFraction = when {
            ui.autoMode -> 0.7f
            ui.fanSpeed != null -> ui.fanSpeed / 10f
            else -> 0.5f
        },
        heating = ui.heating,
        nightMode = ui.nightMode,
        oscillating = ui.oscillation,
        dark = palette.dark,
        offline = ui.status == WidgetStatus.OFFLINE,
    )

    val bitmap = DysonRenderCache.render(
        widthPx = (metrics.dysonWidth.value * density).roundToInt(),
        heightPx = (metrics.dysonHeight.value * density).roundToInt(),
        visual = visual,
    )

    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "Ouvrir ${ui.deviceName}",
        contentScale = ContentScale.Fit,
        modifier = GlanceModifier
            .width(metrics.dysonWidth)
            .height(metrics.dysonHeight)
            .clickable(actionStartActivity<MainActivity>()),
    )
}

@Composable
private fun NotConfigured(palette: GlassPalette, metrics: WidgetMetrics) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        WidgetWordmark(palette, metrics.wordmarkSize)
        VGap(6.dp)
        Text(
            text = "Appuyez pour connecter",
            style = TextStyle(color = palette.secondary, fontSize = metrics.statusSize),
        )
    }
}

/** Glance's Spacer, aliased so the layout above reads cleanly. */
@Composable
private fun Spacer(modifier: GlanceModifier) = androidx.glance.layout.Spacer(modifier)
