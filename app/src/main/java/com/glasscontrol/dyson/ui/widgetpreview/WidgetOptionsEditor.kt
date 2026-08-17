package com.glasscontrol.dyson.ui.widgetpreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glasscontrol.dyson.data.store.QuickControl
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetTheme
import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.ui.components.GlassCard
import com.glasscontrol.dyson.ui.theme.LocalGlassColors

/** Theme, transparency, sensors and quick-control choices for one widget. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetOptionsEditor(
    config: WidgetConfig,
    capabilities: DysonCapabilities,
    onConfigChange: (WidgetConfig) -> Unit,
) {
    val glass = LocalGlassColors.current

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Thème", style = MaterialTheme.typography.titleMedium, color = glass.onSurface)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Transparence du verre",
                    style = MaterialTheme.typography.titleMedium,
                    color = glass.onSurface,
                )
                Text(
                    text = "${(config.glassOpacity * 100).toInt()} %",
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.onSurfaceMuted,
                )
            }
            Slider(
                value = config.glassOpacity,
                onValueChange = { onConfigChange(config.copy(glassOpacity = it)) },
                valueRange = 0f..1f,
                // Five steps, matching the five panel drawables the widget can use.
                steps = 3,
                colors = SliderDefaults.colors(
                    thumbColor = glass.accent,
                    activeTrackColor = glass.accent,
                    inactiveTrackColor = glass.border,
                ),
            )
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Afficher les capteurs",
                    style = MaterialTheme.typography.titleMedium,
                    color = glass.onSurface,
                )
                Text(
                    text = "Température, qualité de l'air, filtre.",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.onSurfaceMuted,
                )
            }
            Switch(
                checked = config.showSensors,
                onCheckedChange = { onConfigChange(config.copy(showSensors = it)) },
            )
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column {
            Text(
                text = "Commandes rapides",
                style = MaterialTheme.typography.titleMedium,
                color = glass.onSurface,
            )
            Text(
                text = "Seules les fonctions prises en charge par votre appareil sont proposées.",
                style = MaterialTheme.typography.labelSmall,
                color = glass.onSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickControl.entries
                    .filter { it.isSupported(capabilities) }
                    .forEach { control ->
                        val selected = control in config.quickControls
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val updated = if (selected) {
                                    config.quickControls - control
                                } else {
                                    config.quickControls + control
                                }
                                // At least one control, otherwise the widget is inert.
                                if (updated.isNotEmpty()) {
                                    onConfigChange(config.copy(quickControls = updated))
                                }
                            },
                            label = { Text(control.label()) },
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
}

private fun themeLabel(theme: WidgetTheme): String = when (theme) {
    WidgetTheme.LIGHT -> "Clair"
    WidgetTheme.DARK -> "Sombre"
    WidgetTheme.AUTO -> "Automatique"
}
