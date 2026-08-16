package com.radardeal.app.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radardeal.app.BuildConfig
import com.radardeal.app.R
import com.radardeal.app.data.prefs.OpenTarget
import com.radardeal.app.domain.model.ScanFrequency
import com.radardeal.app.ui.components.RdCard
import com.radardeal.app.ui.components.RdFilterChip
import com.radardeal.app.ui.components.SecondaryButton
import com.radardeal.app.ui.components.SectionHeader
import com.radardeal.app.ui.radarViewModel
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors

/** Preferences, Vinted session management, history reset and the legal notice. */
@Composable
fun SettingsScreen(onOpenLogin: () -> Unit) {
    val spacing = LocalSpacing.current
    val viewModel = radarViewModel { graph ->
        SettingsViewModel(
            appContext = graph.appContext,
            settingsStore = graph.settingsStore,
            listingRepository = graph.listingRepository,
            session = graph.session,
            notifications = graph.notifications,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(RadarColors.Background),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            bottom = spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "title") {
            Text(
                text = "Réglages",
                style = MaterialTheme.typography.displayMedium,
                color = RadarColors.TextPrimary,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = spacing.xl, bottom = spacing.sm),
            )
        }

        item(key = "notifications-header") { SectionHeader(title = "Notifications") }

        item(key = "notifications") {
            RdCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SwitchRow(
                        title = "Notifications",
                        subtitle = "Alerter à chaque nouvelle annonce et baisse de prix",
                        checked = state.settings.notificationsEnabled,
                        onCheckedChange = viewModel::setNotificationsEnabled,
                    )
                    SwitchRow(
                        title = "Son",
                        subtitle = "Jouer un son à la réception d'une alerte",
                        checked = state.settings.soundEnabled,
                        enabled = state.settings.notificationsEnabled,
                        onCheckedChange = viewModel::setSoundEnabled,
                    )
                    SwitchRow(
                        title = "Vibration",
                        subtitle = "Vibrer à la réception d'une alerte",
                        checked = state.settings.vibrationEnabled,
                        enabled = state.settings.notificationsEnabled,
                        onCheckedChange = viewModel::setVibrationEnabled,
                    )
                }
            }
        }

        item(key = "frequency-header") { SectionHeader(title = "Fréquence par défaut") }

        item(key = "frequency") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    ScanFrequency.entries.forEach { frequency ->
                        RdFilterChip(
                            label = frequency.label,
                            selected = state.settings.defaultIntervalSeconds == frequency.seconds,
                            onClick = { viewModel.setDefaultFrequency(frequency) },
                        )
                    }
                }
                Text(
                    text = "Appliquée aux nouvelles veilles. Les veilles existantes gardent la leur.",
                    style = MaterialTheme.typography.labelSmall,
                    color = RadarColors.TextTertiary,
                )
            }
        }

        item(key = "open-header") { SectionHeader(title = "Ouvrir les annonces dans") }

        item(key = "open") {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                RdFilterChip(
                    label = "RadarDeal",
                    selected = state.settings.openTarget == OpenTarget.IN_APP,
                    onClick = { viewModel.setOpenTarget(OpenTarget.IN_APP) },
                )
                RdFilterChip(
                    label = "Vinted / navigateur",
                    selected = state.settings.openTarget == OpenTarget.EXTERNAL,
                    onClick = { viewModel.setOpenTarget(OpenTarget.EXTERNAL) },
                )
            }
        }

        item(key = "session-header") { SectionHeader(title = "Session Vinted") }

        item(key = "session") {
            RdCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    Text(
                        text = if (state.hasSession) "Session Vinted active" else "Aucune session Vinted",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (state.hasSession) RadarColors.Accent else RadarColors.TextPrimary,
                    )
                    Text(
                        text = "RadarDeal utilise ta session Vinted normale, exactement comme un " +
                            "navigateur. Tu te connectes toi-même sur la page officielle de Vinted : " +
                            "l'application ne voit jamais ton mot de passe, et les cookies restent " +
                            "sur ce téléphone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RadarColors.TextSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        SecondaryButton(
                            text = if (state.hasSession) "Rouvrir Vinted" else "Se connecter",
                            onClick = onOpenLogin,
                            icon = Icons.Rounded.Login,
                            contentColor = RadarColors.Accent,
                        )
                        if (state.hasSession) {
                            SecondaryButton(
                                text = "Déconnecter",
                                onClick = { confirmSignOut = true },
                                icon = Icons.Rounded.Logout,
                                contentColor = RadarColors.Danger,
                            )
                        }
                    }
                }
            }
        }

        item(key = "data-header") { SectionHeader(title = "Données") }

        item(key = "data") {
            RdCard(modifier = Modifier.fillMaxWidth(), onClick = { confirmClear = true }) {
                Row(
                    modifier = Modifier.padding(spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        tint = RadarColors.Danger,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Effacer l'historique",
                            style = MaterialTheme.typography.titleLarge,
                            color = RadarColors.TextPrimary,
                        )
                        Text(
                            text = "Supprime les annonces collectées et l'historique des prix. " +
                                "Les veilles et les favoris sont conservés.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RadarColors.TextSecondary,
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = RadarColors.TextTertiary,
                    )
                }
            }
        }

        item(key = "about-header") { SectionHeader(title = "À propos") }

        item(key = "about") {
            RdCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = "RadarDeal ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleLarge,
                        color = RadarColors.TextPrimary,
                    )
                    Text(
                        text = "Moniteur d'annonces Vinted. Paiement unique, aucun abonnement, " +
                            "aucun compte à créer. Toutes tes données — veilles, favoris, " +
                            "historique, prix et session — restent sur ce téléphone : RadarDeal " +
                            "n'a aucun serveur.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RadarColors.TextSecondary,
                    )
                    Text(
                        text = stringResource(R.string.disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = RadarColors.TextTertiary,
                    )
                }
            }
        }

        // Debug builds only; BuildConfig.VERBOSE_LOGGING is a compile-time constant so R8
        // strips this entirely from the release APK.
        if (BuildConfig.VERBOSE_LOGGING) {
            item(key = "diagnostics-header") { SectionHeader(title = "Diagnostics") }
            item(key = "diagnostics") { UltraDiagnosticsPanel() }
        }

        item(key = "limits") {
            RdCard(
                modifier = Modifier.fillMaxWidth(),
                color = RadarColors.SurfaceElevated,
                border = null,
            ) {
                Column(
                    modifier = Modifier.padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = "Ce qu'Android autorise",
                        style = MaterialTheme.typography.titleMedium,
                        color = RadarColors.TextPrimary,
                    )
                    Text(
                        text = "Le mode Ultra tourne à pleine vitesse (≈ 3 s) tant que RadarDeal " +
                            "est actif avec sa notification permanente. Quand l'application passe " +
                            "en arrière-plan ou que l'écran s'éteint, Android peut réduire cette " +
                            "fréquence — aucune application ne peut l'en empêcher.\n\n" +
                            "Si Android interrompt le service (économie de batterie, mémoire, " +
                            "quota système), RadarDeal continue à scanner environ toutes les 15 " +
                            "minutes et relance le service dès qu'il le peut.\n\n" +
                            "Pour une surveillance vraiment continue, autorise RadarDeal à " +
                            "s'exécuter sans restriction de batterie dans les réglages Android.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RadarColors.TextSecondary,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Effacer l'historique ?",
            message = "Les annonces collectées et l'historique des prix seront supprimés. " +
                "Tes veilles et tes favoris sont conservés. Le prochain scan de chaque veille " +
                "servira de nouvelle référence.",
            confirmLabel = "Effacer",
            onConfirm = {
                viewModel.clearHistory()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Déconnecter Vinted ?",
            message = "Les cookies de session Vinted seront supprimés de ce téléphone. " +
                "Tu devras te reconnecter pour que la surveillance reprenne.",
            confirmLabel = "Déconnecter",
            onConfirm = {
                viewModel.signOutOfVinted()
                confirmSignOut = false
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = if (enabled) RadarColors.TextPrimary else RadarColors.TextTertiary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = RadarColors.TextSecondary,
            )
        }
        Switch(
            checked = checked && enabled,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF00251E),
                checkedTrackColor = RadarColors.Accent,
                uncheckedThumbColor = RadarColors.TextSecondary,
                uncheckedTrackColor = RadarColors.SurfaceElevated,
                uncheckedBorderColor = RadarColors.Outline,
            ),
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RadarColors.SurfaceElevated,
        titleContentColor = RadarColors.TextPrimary,
        textContentColor = RadarColors.TextSecondary,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = RadarColors.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = RadarColors.TextSecondary)
            }
        },
    )
}
