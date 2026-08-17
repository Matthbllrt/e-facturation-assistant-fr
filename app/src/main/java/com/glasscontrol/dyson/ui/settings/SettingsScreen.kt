package com.glasscontrol.dyson.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasscontrol.dyson.ui.components.GlassCard
import com.glasscontrol.dyson.ui.theme.AppTheme
import com.glasscontrol.dyson.ui.theme.LocalGlassColors

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onDeviceForgotten: () -> Unit) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val glass = LocalGlassColors.current
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "Réglages",
            style = MaterialTheme.typography.headlineMedium,
            color = glass.onSurface,
            modifier = Modifier.padding(top = 18.dp, bottom = 16.dp),
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Thème", style = MaterialTheme.typography.titleMedium, color = glass.onSurface)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = prefs.theme == theme,
                            onClick = { viewModel.setTheme(theme) },
                            label = { Text(themeLabel(theme)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = glass.accentSoft,
                                selectedLabelColor = glass.accent,
                                labelColor = glass.onSurfaceMuted,
                            ),
                        )
                    }
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Rafraîchissement en arrière-plan",
                        style = MaterialTheme.typography.titleMedium,
                        color = glass.onSurface,
                    )
                    Text(
                        text = "Met le widget à jour environ toutes les 30 minutes lorsque " +
                            "Android l'autorise. Aucune connexion permanente n'est maintenue.",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.onSurfaceMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Switch(
                    checked = prefs.backgroundRefresh,
                    onCheckedChange = viewModel::setBackgroundRefresh,
                )
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Column {
                Text("Connexion", style = MaterialTheme.typography.titleMedium, color = glass.onSurface)
                TextButton(onClick = viewModel::reconnect, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Se reconnecter maintenant", color = glass.accent)
                }
                TextButton(onClick = { confirmReset = true }) {
                    Text("Supprimer les identifiants et réinitialiser", color = glass.danger)
                }
            }
        }

        Text(
            text = "Les identifiants locaux sont chiffrés par le Keystore Android et ne " +
                "quittent jamais l'appareil. Aucun serveur tiers n'est utilisé.",
            style = MaterialTheme.typography.labelSmall,
            color = glass.onSurfaceMuted,
            modifier = Modifier.padding(top = 18.dp, bottom = 28.dp),
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Réinitialiser ?") },
            text = {
                Text(
                    "Le Dyson configuré sera oublié et son identifiant local supprimé " +
                        "du Keystore. Vous devrez refaire la configuration."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.forgetDevice(onDeviceForgotten)
                }) {
                    Text("Supprimer", color = glass.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Annuler") }
            },
        )
    }
}

private fun themeLabel(theme: AppTheme): String = when (theme) {
    AppTheme.LIGHT -> "Clair"
    AppTheme.DARK -> "Sombre"
    AppTheme.SYSTEM -> "Automatique"
}
