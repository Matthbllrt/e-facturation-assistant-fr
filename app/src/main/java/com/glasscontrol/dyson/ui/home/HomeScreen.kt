package com.glasscontrol.dyson.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasscontrol.dyson.R
import com.glasscontrol.dyson.domain.model.AirQuality
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.ui.components.AmbientGlow
import com.glasscontrol.dyson.ui.components.AnimatedDyson
import com.glasscontrol.dyson.ui.components.GlassCard
import com.glasscontrol.dyson.ui.components.GlassControlButton
import com.glasscontrol.dyson.ui.components.ReadoutRow
import com.glasscontrol.dyson.ui.theme.LocalGlassColors
import kotlin.math.roundToInt

/** The full control surface: the machine, its readings and every supported control. */
@Composable
fun HomeScreen(viewModel: DysonViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val glass = LocalGlassColors.current

    // A live MQTT session runs only while this screen is on screen.
    DisposableEffect(Unit) {
        viewModel.startLiveUpdates()
        onDispose { viewModel.stopLiveUpdates() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ui.device?.name ?: "Dyson",
                    style = MaterialTheme.typography.headlineMedium,
                    color = glass.onSurface,
                )
                Text(
                    text = statusLabel(ui.state.connection),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (ui.state.connection) {
                        ConnectionStatus.ONLINE -> glass.accent
                        ConnectionStatus.OFFLINE -> glass.danger
                        else -> glass.onSurfaceMuted
                    },
                )
            }
            GlassControlButton(
                icon = painterResource(R.drawable.ic_refresh),
                label = "Actualiser",
                active = false,
                size = 48.dp,
                onClick = viewModel::refresh,
            )
        }

        Box(contentAlignment = Alignment.Center) {
            if (ui.state.power) {
                AmbientGlow(
                    color = if (ui.state.heating) glass.warm else glass.accent,
                    intensity = if (ui.state.nightMode) 0.4f else 1f,
                    modifier = Modifier.size(300.dp),
                )
            }
            AnimatedDyson(
                state = ui.state,
                modifier = Modifier
                    .size(width = 230.dp, height = 330.dp),
            )
        }

        // Speed
        if (ui.capabilities.fanSpeed) {
            GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Vitesse", style = MaterialTheme.typography.bodyMedium, color = glass.onSurfaceMuted)
                        Text(
                            text = when {
                                ui.state.autoMode -> "AUTO"
                                ui.state.fanSpeed != null -> "${ui.state.fanSpeed}"
                                else -> "—"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = glass.onSurface,
                        )
                    }
                    Slider(
                        value = (ui.state.fanSpeed ?: 0).toFloat(),
                        onValueChange = { viewModel.setSpeed(it.roundToInt()) },
                        valueRange = 1f..ui.capabilities.maxFanSpeed.toFloat(),
                        steps = ui.capabilities.maxFanSpeed - 2,
                        colors = SliderDefaults.colors(
                            thumbColor = glass.accent,
                            activeTrackColor = glass.accent,
                            inactiveTrackColor = glass.border,
                        ),
                    )
                }
            }
        }

        // Controls, each shown only when the machine supports it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (ui.capabilities.power) {
                GlassControlButton(
                    icon = painterResource(R.drawable.ic_power),
                    label = "Power",
                    active = ui.state.power,
                    onClick = viewModel::togglePower,
                )
            }
            if (ui.capabilities.autoMode) {
                GlassControlButton(
                    icon = painterResource(R.drawable.ic_auto),
                    label = "Auto",
                    active = ui.state.autoMode,
                    onClick = viewModel::toggleAuto,
                )
            }
            if (ui.capabilities.oscillation) {
                GlassControlButton(
                    icon = painterResource(R.drawable.ic_oscillation),
                    label = "Oscillation",
                    active = ui.state.oscillation,
                    onClick = viewModel::toggleOscillation,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (ui.capabilities.nightMode) {
                GlassControlButton(
                    icon = painterResource(R.drawable.ic_night),
                    label = "Nuit",
                    active = ui.state.nightMode,
                    onClick = viewModel::toggleNight,
                )
            }
            if (ui.capabilities.heating) {
                GlassControlButton(
                    icon = painterResource(R.drawable.ic_heat),
                    label = "Chauffage",
                    active = ui.state.heating,
                    accent = glass.warm,
                    onClick = viewModel::toggleHeating,
                )
            }
            if (ui.capabilities.airflowDirection) {
                GlassControlButton(
                    icon = painterResource(R.drawable.ic_wind),
                    label = if (ui.state.frontAirflow) "Avant" else "Arrière",
                    active = ui.state.frontAirflow,
                    onClick = { viewModel.setAirflowFront(!ui.state.frontAirflow) },
                )
            }
        }

        // Target temperature, heating machines only.
        if (ui.capabilities.heating && ui.capabilities.targetTemperature) {
            GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Consigne", style = MaterialTheme.typography.bodyMedium, color = glass.onSurfaceMuted)
                        Text(
                            text = ui.state.targetTemperatureC?.let { "${it.roundToInt()} °C" } ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            color = glass.warm,
                        )
                    }
                    Slider(
                        value = ui.state.targetTemperatureC ?: 20f,
                        onValueChange = viewModel::setTemperature,
                        valueRange = 1f..37f,
                        colors = SliderDefaults.colors(
                            thumbColor = glass.warm,
                            activeTrackColor = glass.warm,
                            inactiveTrackColor = glass.border,
                        ),
                    )
                }
            }
        }

        SensorCard(ui)

        Box(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun SensorCard(ui: DysonUiState) {
    val glass = LocalGlassColors.current
    val capabilities = ui.capabilities
    val state = ui.state
    if (!capabilities.hasAnySensor) return

    GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Column {
            Text(
                text = "Qualité de l'air",
                style = MaterialTheme.typography.titleMedium,
                color = glass.onSurface,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            if (state.airQuality != AirQuality.UNKNOWN) {
                ReadoutRow("Indice global", airQualityLabel(state.airQuality), accent = true)
            }
            if (capabilities.temperature) {
                state.temperatureC?.let { ReadoutRow("Température", "${it.roundToInt()} °C") }
            }
            if (capabilities.humidity) {
                state.humidity?.let { ReadoutRow("Humidité", "$it %") }
            }
            if (capabilities.pm25) {
                state.pm25?.let { ReadoutRow("PM2.5", "$it µg/m³") }
            }
            if (capabilities.pm10) {
                state.pm10?.let { ReadoutRow("PM10", "$it µg/m³") }
            }
            if (capabilities.voc) {
                state.voc?.let { ReadoutRow("COV", formatIndex(it)) }
            }
            if (capabilities.no2) {
                state.no2?.let { ReadoutRow("NO₂", formatIndex(it)) }
            }
            if (capabilities.hepaFilter) {
                state.hepaFilterPercent?.let { ReadoutRow("Filtre HEPA", "$it %") }
            }
            if (capabilities.carbonFilter) {
                state.carbonFilterPercent?.let { ReadoutRow("Filtre carbone", "$it %") }
            }
            if (capabilities.filterHours) {
                state.filterHours?.let { ReadoutRow("Filtre restant", "$it h") }
            }

            if (state.lastUpdatedEpochMs == 0L) {
                Text(
                    text = "En attente des premières mesures…",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

private fun formatIndex(value: Float): String =
    if (value % 1f == 0f) {
        value.roundToInt().toString()
    } else {
        // Explicit locale: the decimal separator should follow the user's setting.
        String.format(java.util.Locale.getDefault(), "%.1f", value)
    }

internal fun airQualityLabel(quality: AirQuality): String = when (quality) {
    AirQuality.GOOD -> "Bonne"
    AirQuality.FAIR -> "Moyenne"
    AirQuality.POOR -> "Mauvaise"
    AirQuality.VERY_POOR -> "Très mauvaise"
    AirQuality.UNKNOWN -> "—"
}

private fun statusLabel(connection: ConnectionStatus): String = when (connection) {
    ConnectionStatus.ONLINE -> "Connecté"
    ConnectionStatus.CONNECTING -> "Connexion…"
    ConnectionStatus.OFFLINE -> "Hors ligne"
    ConnectionStatus.UNKNOWN -> "État inconnu"
}
