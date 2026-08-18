package com.glasscontrol.dyson.ui.widgetpreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetTheme
import com.glasscontrol.dyson.ui.components.GlassCard
import com.glasscontrol.dyson.ui.theme.LocalGlassColors

/**
 * The few choices a widget instance actually offers.
 *
 * Deliberately short: the composition itself is fixed by design, so the only
 * meaningful decisions left are how it should sit against the wallpaper and what
 * to call the machine.
 */
@Composable
fun WidgetOptionsEditor(
    config: WidgetConfig,
    deviceName: String,
    onConfigChange: (WidgetConfig) -> Unit,
) {
    val glass = LocalGlassColors.current

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Apparence", style = MaterialTheme.typography.titleMedium, color = glass.onSurface)
            Text(
                text = "Le verre s'adapte automatiquement au thème du téléphone, ou " +
                    "forcez-le si votre fond d'écran le demande.",
                style = MaterialTheme.typography.labelSmall,
                color = glass.onSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WidgetTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = config.theme == theme,
                        onClick = { onConfigChange(config.copy(theme = theme)) },
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
        Column {
            Text("Appareil", style = MaterialTheme.typography.titleMedium, color = glass.onSurface)
            Text(
                text = deviceName,
                style = MaterialTheme.typography.bodyMedium,
                color = glass.accent,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = config.customName.orEmpty(),
                onValueChange = {
                    onConfigChange(config.copy(customName = it.takeIf { name -> name.isNotBlank() }))
                },
                label = { Text("Nom affiché (facultatif)", color = glass.onSurfaceMuted) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = glass.onSurface,
                    unfocusedTextColor = glass.onSurface,
                    focusedBorderColor = glass.accent,
                    unfocusedBorderColor = glass.border,
                    cursorColor = glass.accent,
                ),
            )
        }
    }
}

private fun themeLabel(theme: WidgetTheme): String = when (theme) {
    WidgetTheme.LIGHT -> "Clair"
    WidgetTheme.DARK -> "Sombre"
    WidgetTheme.AUTO -> "Automatique"
}
