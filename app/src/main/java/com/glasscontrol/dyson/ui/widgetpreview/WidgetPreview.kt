package com.glasscontrol.dyson.ui.widgetpreview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasscontrol.dyson.R
import com.glasscontrol.dyson.data.store.QuickControl
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetTheme
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.domain.model.DysonState
import com.glasscontrol.dyson.ui.components.AnimatedDyson
import kotlin.math.roundToInt

/**
 * An in-app replica of the home-screen widget.
 *
 * It mirrors the Glance layout so the configuration screen can show the effect
 * of a change immediately — Glance itself cannot be composed inside the app.
 */
@Composable
fun WidgetPreview(
    config: WidgetConfig,
    state: DysonState,
    capabilities: DysonCapabilities,
    deviceName: String,
    modelName: String,
    hero: Boolean,
    systemDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = when (config.theme) {
        WidgetTheme.DARK -> true
        WidgetTheme.LIGHT -> false
        WidgetTheme.AUTO -> systemDark
    }
    val opacity = config.glassOpacity.coerceIn(0f, 1f)
    val panelBrush = if (dark) {
        Brush.verticalGradient(
            0f to Color(0xFF3D4756).copy(alpha = opacity * 0.62f),
            0.5f to Color(0xFF18202B).copy(alpha = opacity * 0.80f),
            1f to Color(0xFF0A0F16).copy(alpha = opacity),
        )
    } else {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = opacity),
            0.5f to Color(0xFFF2F6FA).copy(alpha = opacity * 0.88f),
            1f to Color(0xFFDCE5EE).copy(alpha = opacity * 0.72f),
        )
    }
    val primary = if (dark) Color(0xFFF2F7FB) else Color(0xFF10161D)
    val secondary = if (dark) Color(0xFFC7D6E2) else Color(0xFF2C3742)
    val accent = if (dark) Color(0xFF7FE3F5) else Color(0xFF0E7C93)
    val shape = RoundedCornerShape(30.dp)

    Box(
        modifier = modifier
            .background(panelBrush, shape)
            .border(
                BorderStroke(1.dp, Color(0xFFA8C4D8).copy(alpha = opacity * 0.55f)),
                shape,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val controls = config.quickControls.filter { it.isSupported(capabilities) }

        if (hero) {
            Column(modifier = Modifier.fillMaxWidth()) {
                PreviewHeader(deviceName, modelName, state, capabilities, primary, secondary, accent)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (config.showSensors) {
                            PreviewReadouts(state, capabilities, secondary, primary, accent)
                        }
                    }
                    AnimatedDyson(state = state, modifier = Modifier.size(92.dp, 138.dp))
                }
                Spacer(Modifier.height(10.dp))
                if (capabilities.fanSpeed) {
                    PreviewSpeedRow(state, primary, dark)
                    Spacer(Modifier.height(8.dp))
                }
                PreviewControls(controls, state, dark, accent, primary)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedDyson(state = state, modifier = Modifier.size(52.dp, 86.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    PreviewHeader(deviceName, modelName, state, capabilities, primary, secondary, accent)
                    Spacer(Modifier.height(8.dp))
                    if (capabilities.fanSpeed) {
                        PreviewSpeedRow(state, primary, dark)
                        Spacer(Modifier.height(6.dp))
                    }
                    PreviewControls(controls, state, dark, accent, primary)
                }
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    deviceName: String,
    modelName: String,
    state: DysonState,
    capabilities: DysonCapabilities,
    primary: Color,
    secondary: Color,
    accent: Color,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("D Y S O N", color = primary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            state.temperatureC?.takeIf { capabilities.temperature }?.let {
                Text("${it.roundToInt()}°", color = primary, fontSize = 20.sp)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(modelName, color = secondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            val (label, color) = when (state.connection) {
                ConnectionStatus.ONLINE -> "Online" to accent
                ConnectionStatus.OFFLINE -> "Offline" to Color(0xFFFF9B9B)
                else -> "—" to secondary
            }
            Text("● $label", color = color, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PreviewReadouts(
    state: DysonState,
    capabilities: DysonCapabilities,
    secondary: Color,
    primary: Color,
    accent: Color,
) {
    @Composable
    fun row(label: String, value: String, highlight: Boolean = false) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = secondary, fontSize = 11.sp)
            Text(value, color = if (highlight) accent else primary, fontSize = 12.sp)
        }
    }

    if (capabilities.hasAnySensor) {
        row("Air", com.glasscontrol.dyson.ui.home.airQualityLabel(state.airQuality), highlight = true)
    }
    if (capabilities.pm25) state.pm25?.let { row("PM2.5", "$it µg") }
    if (capabilities.humidity) state.humidity?.let { row("Humidité", "$it %") }
    state.filterPercent?.let { row("Filtre", "$it %") }
}

@Composable
private fun PreviewSpeedRow(state: DysonState, primary: Color, dark: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PreviewChip(R.drawable.ic_minus, active = false, dark = dark, tint = primary)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = when {
                    state.autoMode -> "AUTO"
                    state.fanSpeed != null -> state.fanSpeed.toString().padStart(2, '0')
                    else -> "--"
                },
                color = primary,
                fontSize = 15.sp,
            )
        }
        PreviewChip(R.drawable.ic_plus, active = false, dark = dark, tint = primary)
    }
}

@Composable
private fun PreviewControls(
    controls: List<QuickControl>,
    state: DysonState,
    dark: Boolean,
    accent: Color,
    primary: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        controls.forEach { control ->
            val active = control.isActive(state)
            Box(modifier = Modifier.weight(1f)) {
                PreviewChip(
                    iconRes = control.iconRes(),
                    active = active,
                    dark = dark,
                    tint = if (active) accent else primary,
                    fillWidth = true,
                )
            }
        }
    }
}

@Composable
private fun PreviewChip(
    iconRes: Int,
    active: Boolean,
    dark: Boolean,
    tint: Color,
    fillWidth: Boolean = false,
) {
    val shape = RoundedCornerShape(22.dp)
    val background = when {
        active -> tint.copy(alpha = 0.24f)
        dark -> Color.White.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.22f)
    }
    Box(
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(44.dp))
            .height(38.dp)
            .background(background, shape)
            .border(BorderStroke(1.dp, tint.copy(alpha = if (active) 0.55f else 0.20f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

internal fun QuickControl.isSupported(capabilities: DysonCapabilities): Boolean = when (this) {
    QuickControl.POWER -> capabilities.power
    QuickControl.AUTO -> capabilities.autoMode
    QuickControl.OSCILLATION -> capabilities.oscillation
    QuickControl.NIGHT -> capabilities.nightMode
    QuickControl.SPEED -> capabilities.fanSpeed
    QuickControl.HEAT -> capabilities.heating
}

internal fun QuickControl.isActive(state: DysonState): Boolean = when (this) {
    QuickControl.POWER -> state.power
    QuickControl.AUTO -> state.autoMode
    QuickControl.OSCILLATION -> state.oscillation
    QuickControl.NIGHT -> state.nightMode
    QuickControl.SPEED -> false
    QuickControl.HEAT -> state.heating
}

internal fun QuickControl.iconRes(): Int = when (this) {
    QuickControl.POWER -> R.drawable.ic_power
    QuickControl.AUTO -> R.drawable.ic_auto
    QuickControl.OSCILLATION -> R.drawable.ic_oscillation
    QuickControl.NIGHT -> R.drawable.ic_night
    QuickControl.SPEED -> R.drawable.ic_wind
    QuickControl.HEAT -> R.drawable.ic_heat
}

internal fun QuickControl.label(): String = when (this) {
    QuickControl.POWER -> "Power"
    QuickControl.AUTO -> "Auto"
    QuickControl.OSCILLATION -> "Oscillation"
    QuickControl.NIGHT -> "Nuit"
    QuickControl.SPEED -> "Vitesse"
    QuickControl.HEAT -> "Chauffage"
}
