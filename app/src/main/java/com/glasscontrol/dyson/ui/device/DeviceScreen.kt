package com.glasscontrol.dyson.ui.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.ui.components.GlassCard
import com.glasscontrol.dyson.ui.components.ReadoutRow
import com.glasscontrol.dyson.ui.home.DysonViewModel
import com.glasscontrol.dyson.ui.theme.LocalGlassColors

/** Identity, connection and the capabilities actually detected on this machine. */
@Composable
fun DeviceScreen(viewModel: DysonViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val glass = LocalGlassColors.current
    val device = ui.device

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "Appareil",
            style = MaterialTheme.typography.headlineMedium,
            color = glass.onSurface,
            modifier = Modifier.padding(top = 18.dp, bottom = 16.dp),
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                ReadoutRow("Nom", device?.name ?: "—")
                ReadoutRow("Modèle", device?.displayModel ?: "—")
                ReadoutRow("Type MQTT", device?.deviceType ?: "—")
                ReadoutRow("Numéro de série", device?.serial ?: "—")
                ReadoutRow("Adresse locale", device?.host ?: "Recherche automatique")
                device?.firmware?.let { ReadoutRow("Micrologiciel", it) }
                ReadoutRow(
                    label = "Connexion",
                    value = when (ui.state.connection) {
                        ConnectionStatus.ONLINE -> "Connecté"
                        ConnectionStatus.CONNECTING -> "Connexion…"
                        ConnectionStatus.OFFLINE -> "Hors ligne"
                        ConnectionStatus.UNKNOWN -> "Inconnue"
                    },
                    accent = ui.state.connection == ConnectionStatus.ONLINE,
                )
            }
        }

        Text(
            text = "Fonctions détectées",
            style = MaterialTheme.typography.titleMedium,
            color = glass.onSurface,
            modifier = Modifier.padding(top = 24.dp, bottom = 10.dp),
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                capabilityList(ui.capabilities).forEach { (label, supported) ->
                    ReadoutRow(label, if (supported) "Oui" else "Non", accent = supported)
                }
            }
        }

        Text(
            text = "Les commandes non prises en charge par ce modèle sont automatiquement " +
                "masquées dans l'application et dans le widget.",
            style = MaterialTheme.typography.labelSmall,
            color = glass.onSurfaceMuted,
            modifier = Modifier.padding(top = 14.dp, bottom = 28.dp),
        )
    }
}

private fun capabilityList(capabilities: DysonCapabilities): List<Pair<String, Boolean>> = listOf(
    "Marche/Arrêt" to capabilities.power,
    "Vitesse 1-${capabilities.maxFanSpeed}" to capabilities.fanSpeed,
    "Mode auto" to capabilities.autoMode,
    "Oscillation" to capabilities.oscillation,
    "Mode nuit" to capabilities.nightMode,
    "Chauffage" to capabilities.heating,
    "Consigne de température" to capabilities.targetTemperature,
    "Direction du flux" to capabilities.airflowDirection,
    "Température" to capabilities.temperature,
    "Humidité" to capabilities.humidity,
    "PM2.5" to capabilities.pm25,
    "PM10" to capabilities.pm10,
    "COV" to capabilities.voc,
    "NO₂" to capabilities.no2,
    "Filtre HEPA" to capabilities.hepaFilter,
    "Filtre carbone" to capabilities.carbonFilter,
)
