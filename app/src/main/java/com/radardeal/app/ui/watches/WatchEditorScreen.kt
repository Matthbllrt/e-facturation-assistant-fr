package com.radardeal.app.ui.watches

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radardeal.app.domain.model.ScanFrequency
import com.radardeal.app.ui.components.RdCard
import com.radardeal.app.ui.components.RdFilterChip
import com.radardeal.app.ui.components.PrimaryButton
import com.radardeal.app.ui.components.SectionHeader
import com.radardeal.app.ui.radarViewModel
import com.radardeal.app.ui.theme.FieldShape
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors

/**
 * Create or edit a veille.
 *
 * One screen, two ways in: type what you are looking for, or paste a Vinted search URL. The
 * URL route is the powerful one — it keeps every advanced filter Vinted let the user set — and
 * the screen says so rather than hiding it behind a toggle nobody reads.
 */
@Composable
fun WatchEditorScreen(
    watchId: Long?,
    onDone: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val viewModel = radarViewModel(key = "watch-editor-${watchId ?: "new"}") { graph ->
        WatchEditorViewModel(
            appContext = graph.appContext,
            watchId = watchId,
            watchRepository = graph.watchRepository,
            coordinator = graph.coordinator,
            settingsStore = graph.settingsStore,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RadarColors.Background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Fermer",
                    tint = RadarColors.TextSecondary,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Text(
                text = if (state.isEditing) "Modifier la veille" else "Que cherches-tu ?",
                style = MaterialTheme.typography.displayMedium,
                color = RadarColors.TextPrimary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                RdFilterChip(
                    label = "Critères",
                    selected = state.mode == WatchMode.CRITERIA,
                    onClick = { viewModel.setMode(WatchMode.CRITERIA) },
                )
                RdFilterChip(
                    label = "URL Vinted",
                    selected = state.mode == WatchMode.URL,
                    onClick = { viewModel.setMode(WatchMode.URL) },
                )
            }

            if (state.mode == WatchMode.CRITERIA) {
                RdField(
                    value = state.keyword,
                    onValueChange = viewModel::setKeyword,
                    label = "Mot clé",
                    placeholder = "Air Max 95",
                )
                RdField(
                    value = state.brand,
                    onValueChange = viewModel::setBrand,
                    label = "Marque",
                    placeholder = "Nike",
                )
                RdField(
                    value = state.maxPrice,
                    onValueChange = viewModel::setMaxPrice,
                    label = "Prix maximum",
                    placeholder = "80",
                    suffix = "€",
                    keyboardType = KeyboardType.Number,
                )
            } else {
                RdField(
                    value = state.sourceUrl,
                    onValueChange = viewModel::setSourceUrl,
                    label = "URL de recherche Vinted",
                    placeholder = "https://www.vinted.fr/catalog?search_text=...",
                    error = state.urlError,
                    keyboardType = KeyboardType.Uri,
                    singleLine = false,
                )
                RdCard(color = RadarColors.SurfaceElevated, border = null) {
                    Column(
                        modifier = Modifier.padding(spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Text(
                            text = "Pourquoi c'est la meilleure option",
                            style = MaterialTheme.typography.titleMedium,
                            color = RadarColors.TextPrimary,
                        )
                        Text(
                            text = "Lance ta recherche sur Vinted avec tous les filtres que tu " +
                                "veux (catégorie, taille, état, couleur, marque, prix), puis copie " +
                                "l'adresse de la page de résultats. RadarDeal surveillera " +
                                "exactement cette recherche.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RadarColors.TextSecondary,
                        )
                    }
                }
            }

            RdField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = "Nom de la veille (optionnel)",
                placeholder = state.effectiveName,
            )

            SectionHeader(title = "Fréquence")

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                ScanFrequency.entries.forEach { frequency ->
                    FrequencyRow(
                        frequency = frequency,
                        selected = state.frequency == frequency,
                        onClick = { viewModel.setFrequency(frequency) },
                    )
                }
            }

            Text(
                text = if (state.frequency == ScanFrequency.ULTRA) {
                    "Le mode Ultra ne peut être actif que sur une seule veille à la fois, et " +
                        "tourne à pleine vitesse tant que RadarDeal est actif avec sa " +
                        "notification permanente. Android peut réduire la fréquence quand " +
                        "l'application passe en arrière-plan ou que l'écran s'éteint."
                } else {
                    "Les fréquences rapides nécessitent que la surveillance tourne au premier " +
                        "plan, avec la notification permanente « RadarDeal actif ». Android " +
                        "n'autorise aucune application à scanner toutes les quelques secondes " +
                        "en arrière-plan sans elle."
                },
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.TextTertiary,
            )

            Spacer(Modifier.height(spacing.sm))
        }

        Column(
            modifier = Modifier
                .background(RadarColors.Background)
                .padding(horizontal = spacing.screen)
                .padding(top = spacing.md, bottom = spacing.lg)
                .navigationBarsPadding(),
        ) {
            PrimaryButton(
                text = if (state.isEditing) "Enregistrer" else "Activer le radar",
                onClick = viewModel::save,
                enabled = state.canSave,
                icon = Icons.Rounded.Radar,
            )
        }
    }

    state.ultraConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUltraConflict,
            containerColor = RadarColors.SurfaceElevated,
            titleContentColor = RadarColors.TextPrimary,
            textContentColor = RadarColors.TextSecondary,
            title = { Text("Le mode Ultra est déjà utilisé") },
            text = {
                Text(
                    "Le mode Ultra ne peut être actif que sur une seule veille à la fois.\n\n" +
                        "Il est actuellement sur « ${conflict.holderName} ». Veux-tu le " +
                        "transférer à cette veille ?"
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUltraTransfer) {
                    Text("Transférer", color = RadarColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUltraConflict) {
                    Text("Annuler", color = RadarColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun FrequencyRow(
    frequency: ScanFrequency,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val isUltra = frequency == ScanFrequency.ULTRA
    RdCard(
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) RadarColors.AccentSoft else RadarColors.Surface,
        border = null,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${frequency.badge} ${frequency.label}",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (selected) RadarColors.Accent else RadarColors.TextPrimary,
                )
                // The battery cost is stated up front rather than discovered later.
                Text(
                    text = frequency.costHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUltra) RadarColors.Warning else RadarColors.TextTertiary,
                )
            }
            Text(
                text = frequency.description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) RadarColors.Accent else RadarColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun RdField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    suffix: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = {
                Text(placeholder, color = RadarColors.TextTertiary, maxLines = 1)
            },
            suffix = suffix?.let { { Text(it, color = RadarColors.TextSecondary) } },
            isError = error != null,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 3,
            shape = FieldShape,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = RadarColors.TextPrimary,
                unfocusedTextColor = RadarColors.TextPrimary,
                focusedContainerColor = RadarColors.Surface,
                unfocusedContainerColor = RadarColors.Surface,
                errorContainerColor = RadarColors.Surface,
                focusedBorderColor = RadarColors.Accent,
                unfocusedBorderColor = RadarColors.Outline,
                errorBorderColor = RadarColors.Danger,
                focusedLabelColor = RadarColors.Accent,
                unfocusedLabelColor = RadarColors.TextSecondary,
                cursorColor = RadarColors.Accent,
            ),
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.Danger,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp),
            )
        }
    }
}
