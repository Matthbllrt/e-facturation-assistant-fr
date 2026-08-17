package com.glasscontrol.dyson.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import androidx.glance.action.actionParametersOf
import androidx.glance.ColorFilter
import com.glasscontrol.dyson.R
import com.glasscontrol.dyson.data.store.QuickControl
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.domain.model.AirQuality
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.domain.model.DysonState
import com.glasscontrol.dyson.ui.art.DysonArtwork
import com.glasscontrol.dyson.ui.art.DysonVisual
import com.glasscontrol.dyson.ui.MainActivity
import kotlin.math.roundToInt

/** Everything one widget needs to paint itself. */
data class WidgetUiState(
    val deviceName: String,
    val modelName: String,
    val state: DysonState,
    val capabilities: DysonCapabilities,
    val config: WidgetConfig,
    val configured: Boolean,
)

/** The 4x4 widget: full sensor readout, large machine, every control. */
@Composable
fun HeroWidgetContent(ui: WidgetUiState) {
    val context = LocalContext.current
    val theme = ResolvedWidgetTheme.resolve(context, ui.config)

    GlassPanel(theme) {
        if (!ui.configured) {
            NotConfigured(theme)
            return@GlassPanel
        }

        Column(modifier = GlanceModifier.fillMaxSize()) {
            Header(ui, theme, showModel = true)
            Spacer(GlanceModifier.height(10.dp))

            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Readouts(ui, theme)
                }
                DeviceImage(ui, theme, widthDp = 92, heightDp = 138)
            }

            Spacer(GlanceModifier.height(10.dp))
            if (ui.capabilities.fanSpeed) {
                SpeedRow(ui, theme, 38.dp)
                Spacer(GlanceModifier.height(8.dp))
            }
            ControlRow(ui, theme, 38.dp)
        }
    }
}

/** The 4x2 widget: one headline reading, the machine, and quick controls. */
@Composable
fun CompactWidgetContent(ui: WidgetUiState) {
    val context = LocalContext.current
    val theme = ResolvedWidgetTheme.resolve(context, ui.config)
    val size = LocalSize.current
    // A 4x2 cell can be as short as ~110dp on some launchers. Rather than let the
    // layout clip, the speed row is dropped when there is not room for both rows.
    val roomForSpeedRow = size.height >= 132.dp
    val chipHeight = if (size.height >= 132.dp) 34.dp else 32.dp

    GlassPanel(theme) {
        if (!ui.configured) {
            NotConfigured(theme)
            return@GlassPanel
        }

        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            DeviceImage(ui, theme, widthDp = 52, heightDp = 86)
            Spacer(GlanceModifier.width(10.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Header(ui, theme, showModel = size.height >= 120.dp)
                Spacer(GlanceModifier.height(6.dp))
                if (ui.capabilities.fanSpeed && roomForSpeedRow) {
                    SpeedRow(ui, theme, chipHeight)
                    Spacer(GlanceModifier.height(6.dp))
                }
                ControlRow(ui, theme, chipHeight)
            }
        }
    }
}

/** The translucent slab everything sits on. */
@Composable
private fun GlassPanel(theme: ResolvedWidgetTheme, content: @Composable () -> Unit) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(theme.panelRes))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        content()
    }
}

@Composable
private fun Header(ui: WidgetUiState, theme: ResolvedWidgetTheme, showModel: Boolean) {
    val state = ui.state
    Column {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // Letter-spaced wordmark: Glance has no tracking, so the spaces are literal.
            Text(
                text = "D Y S O N",
                style = TextStyle(
                    color = theme.primaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            state.temperatureC?.takeIf { ui.capabilities.temperature }?.let { temperature ->
                Text(
                    text = "${temperature.roundToInt()}°",
                    style = TextStyle(
                        color = theme.primaryText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Actualiser",
                colorFilter = ColorFilter.tint(theme.secondaryText),
                modifier = GlanceModifier
                    .size(16.dp)
                    .clickable(
                        actionRunCallback<DysonCommandAction>(
                            actionParametersOf(WidgetCommands.KEY to WidgetCommands.REFRESH)
                        )
                    ),
            )
        }

        Spacer(GlanceModifier.height(2.dp))

        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            if (showModel) {
                Text(
                    text = ui.modelName,
                    style = TextStyle(color = theme.secondaryText, fontSize = 11.sp),
                    modifier = GlanceModifier.defaultWeight(),
                    maxLines = 1,
                )
            }
            StatusBadge(state.connection, theme)
        }
    }
}

@Composable
private fun StatusBadge(connection: ConnectionStatus, theme: ResolvedWidgetTheme) {
    val (label, color) = when (connection) {
        ConnectionStatus.ONLINE -> "Online" to theme.accentText
        ConnectionStatus.CONNECTING -> "…" to theme.secondaryText
        ConnectionStatus.OFFLINE -> "Offline" to theme.offlineText
        ConnectionStatus.UNKNOWN -> "—" to theme.secondaryText
    }
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(text = "●", style = TextStyle(color = color, fontSize = 8.sp))
        Spacer(GlanceModifier.width(4.dp))
        Text(text = label, style = TextStyle(color = color, fontSize = 11.sp))
    }
}

/** Sensor readings, each one shown only when the machine reports it. */
@Composable
private fun Readouts(ui: WidgetUiState, theme: ResolvedWidgetTheme) {
    if (!ui.config.showSensors) return
    val state = ui.state
    val capabilities = ui.capabilities

    Column {
        val airQuality = state.airQuality
        if (capabilities.hasAnySensor && airQuality != AirQuality.UNKNOWN) {
            Readout(theme, "Air", airQualityLabel(airQuality), highlight = true)
        }
        if (capabilities.pm25) {
            state.pm25?.let { Readout(theme, "PM2.5", "$it µg") }
        }
        if (capabilities.humidity) {
            state.humidity?.let { Readout(theme, "Humidité", "$it %") }
        }
        state.filterPercent?.takeIf { capabilities.hepaFilter || capabilities.carbonFilter }?.let {
            Readout(theme, "Filtre", "$it %")
        }
    }
}

@Composable
private fun Readout(
    theme: ResolvedWidgetTheme,
    label: String,
    value: String,
    highlight: Boolean = false,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = 5.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(color = theme.secondaryText, fontSize = 11.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = value,
            style = TextStyle(
                color = if (highlight) theme.accentText else theme.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/** The machine itself, rendered to a bitmap and opening the app when tapped. */
@Composable
private fun DeviceImage(
    ui: WidgetUiState,
    theme: ResolvedWidgetTheme,
    widthDp: Int,
    heightDp: Int,
) {
    val context = LocalContext.current
    val bitmap = renderDeviceBitmap(ui, theme, widthDp, heightDp, context)

    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "Ouvrir ${ui.deviceName}",
        contentScale = ContentScale.Fit,
        modifier = GlanceModifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            .clickable(actionStartActivity<MainActivity>()),
    )
}

/**
 * Rasterises the artwork at the density the launcher will show it at.
 *
 * The density is capped: a RemoteViews payload has a hard size limit and an
 * over-sampled bitmap is the quickest way to hit it.
 */
private fun renderDeviceBitmap(
    ui: WidgetUiState,
    theme: ResolvedWidgetTheme,
    widthDp: Int,
    heightDp: Int,
    context: Context,
): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density.coerceAtMost(2.0f)
    val state = ui.state
    val visual = DysonVisual(
        on = state.power,
        speedFraction = when {
            state.autoMode -> 0.7f
            state.fanSpeed != null -> state.fanSpeed / 10f
            else -> 0.5f
        },
        heating = state.heating,
        nightMode = state.nightMode,
        oscillating = state.oscillation,
        dark = theme.dark,
    )
    return DysonArtwork.renderBitmap(
        widthPx = (widthDp * density).roundToInt(),
        heightPx = (heightDp * density).roundToInt(),
        visual = visual,
    )
}

/** Minus / current speed / plus. */
@Composable
private fun SpeedRow(ui: WidgetUiState, theme: ResolvedWidgetTheme, chipHeight: Dp) {
    val speedLabel = when {
        ui.state.autoMode -> "AUTO"
        ui.state.fanSpeed != null -> ui.state.fanSpeed.toString().padStart(2, '0')
        else -> "--"
    }

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        IconChip(
            theme = theme,
            iconRes = R.drawable.ic_minus,
            active = false,
            command = WidgetCommands.SPEED_DOWN,
            description = "Diminuer la vitesse",
            chipHeight = chipHeight,
        )
        Box(
            modifier = GlanceModifier.defaultWeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = speedLabel,
                style = TextStyle(
                    color = theme.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        IconChip(
            theme = theme,
            iconRes = R.drawable.ic_plus,
            active = false,
            command = WidgetCommands.SPEED_UP,
            description = "Augmenter la vitesse",
            chipHeight = chipHeight,
        )
    }
}

/** The quick controls the user chose, filtered by what the machine supports. */
@Composable
private fun ControlRow(ui: WidgetUiState, theme: ResolvedWidgetTheme, chipHeight: Dp) {
    val controls = ui.config.quickControls.filter { it.isSupported(ui.capabilities) }
    if (controls.isEmpty()) return

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        controls.forEachIndexed { index, control ->
            if (index > 0) Spacer(GlanceModifier.width(8.dp))
            IconChip(
                theme = theme,
                iconRes = control.iconRes(),
                active = control.isActive(ui.state),
                warm = control == QuickControl.HEAT,
                command = control.command(),
                description = control.label(),
                chipHeight = chipHeight,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }
}

@Composable
private fun IconChip(
    theme: ResolvedWidgetTheme,
    iconRes: Int,
    active: Boolean,
    command: String,
    description: String,
    chipHeight: Dp,
    warm: Boolean = false,
    modifier: GlanceModifier = GlanceModifier,
) {
    val background = when {
        active && warm -> theme.chipWarmRes
        active -> theme.chipActiveRes
        else -> theme.chipRes
    }
    val tint = when {
        active && warm -> theme.iconTintWarm
        active -> theme.iconTintActive
        else -> theme.iconTint
    }

    Box(
        modifier = modifier
            .height(chipHeight)
            .background(ImageProvider(background))
            .clickable(
                actionRunCallback<DysonCommandAction>(
                    actionParametersOf(WidgetCommands.KEY to command)
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(chipHeight * 0.5f),
        )
    }
}

@Composable
private fun NotConfigured(theme: ResolvedWidgetTheme) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text(
            text = "D Y S O N",
            style = TextStyle(color = theme.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            text = "Appuyez pour connecter",
            style = TextStyle(color = theme.secondaryText, fontSize = 12.sp),
        )
    }
}

private fun airQualityLabel(quality: AirQuality): String = when (quality) {
    AirQuality.GOOD -> "GOOD"
    AirQuality.FAIR -> "FAIR"
    AirQuality.POOR -> "POOR"
    AirQuality.VERY_POOR -> "VERY POOR"
    AirQuality.UNKNOWN -> "—"
}

private fun QuickControl.isSupported(capabilities: DysonCapabilities): Boolean = when (this) {
    QuickControl.POWER -> capabilities.power
    QuickControl.AUTO -> capabilities.autoMode
    QuickControl.OSCILLATION -> capabilities.oscillation
    QuickControl.NIGHT -> capabilities.nightMode
    QuickControl.SPEED -> capabilities.fanSpeed
    QuickControl.HEAT -> capabilities.heating
}

private fun QuickControl.isActive(state: DysonState): Boolean = when (this) {
    QuickControl.POWER -> state.power
    QuickControl.AUTO -> state.autoMode
    QuickControl.OSCILLATION -> state.oscillation
    QuickControl.NIGHT -> state.nightMode
    QuickControl.SPEED -> false
    QuickControl.HEAT -> state.heating
}

private fun QuickControl.iconRes(): Int = when (this) {
    QuickControl.POWER -> R.drawable.ic_power
    QuickControl.AUTO -> R.drawable.ic_auto
    QuickControl.OSCILLATION -> R.drawable.ic_oscillation
    QuickControl.NIGHT -> R.drawable.ic_night
    QuickControl.SPEED -> R.drawable.ic_wind
    QuickControl.HEAT -> R.drawable.ic_heat
}

private fun QuickControl.command(): String = when (this) {
    QuickControl.POWER -> WidgetCommands.TOGGLE_POWER
    QuickControl.AUTO -> WidgetCommands.TOGGLE_AUTO
    QuickControl.OSCILLATION -> WidgetCommands.TOGGLE_OSCILLATION
    QuickControl.NIGHT -> WidgetCommands.TOGGLE_NIGHT
    QuickControl.SPEED -> WidgetCommands.SPEED_UP
    QuickControl.HEAT -> WidgetCommands.TOGGLE_HEAT
}

internal fun QuickControl.label(): String = when (this) {
    QuickControl.POWER -> "Marche/Arrêt"
    QuickControl.AUTO -> "Mode auto"
    QuickControl.OSCILLATION -> "Oscillation"
    QuickControl.NIGHT -> "Mode nuit"
    QuickControl.SPEED -> "Vitesse"
    QuickControl.HEAT -> "Chauffage"
}
