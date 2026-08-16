package com.radardeal.app.ui.watches

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radardeal.app.core.Formatters
import com.radardeal.app.data.repository.WatchWithStats
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.ui.components.BadgePill
import com.radardeal.app.ui.components.EmptyState
import com.radardeal.app.ui.components.LiveDot
import com.radardeal.app.ui.components.PrimaryButton
import com.radardeal.app.ui.components.RdCard
import com.radardeal.app.ui.components.SecondaryButton
import com.radardeal.app.ui.components.StatusBanner
import com.radardeal.app.ui.radarViewModel
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors

/** The list of saved searches, with the per-watch actions from the spec. */
@Composable
fun WatchesScreen(
    onCreateWatch: () -> Unit,
    onEditWatch: (Long) -> Unit,
    onOpenLogin: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val viewModel = radarViewModel { graph ->
        WatchesViewModel(
            appContext = graph.appContext,
            watchRepository = graph.watchRepository,
            coordinator = graph.coordinator,
            settingsStore = graph.settingsStore,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var pendingDeletion by remember { mutableStateOf<WatchWithStats?>(null) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Scaffold(
        containerColor = RadarColors.Background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            if (state.watches.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onCreateWatch,
                    containerColor = RadarColors.Accent,
                    contentColor = Color(0xFF00251E),
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Nouvelle veille", style = MaterialTheme.typography.labelLarge) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(RadarColors.Background)
                .padding(padding),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "title") {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(top = spacing.xl, bottom = spacing.sm),
                ) {
                    Text(
                        text = "Veilles",
                        style = MaterialTheme.typography.displayMedium,
                        color = RadarColors.TextPrimary,
                    )
                    Text(
                        text = when (state.watches.size) {
                            0 -> "Aucune recherche enregistrée"
                            1 -> "1 recherche enregistrée"
                            else -> "${state.watches.size} recherches enregistrées"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = RadarColors.TextSecondary,
                    )
                }
            }

            if (state.watches.isEmpty() && !state.isLoading) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.Visibility,
                        title = "Aucune veille",
                        message = "Crée ton premier radar : une marque, un modèle, un prix maximum — " +
                            "ou colle directement une recherche Vinted.",
                        action = { PrimaryButton(text = "Créer un radar", onClick = onCreateWatch) },
                    )
                }
            }

            items(state.watches, key = { it.watch.id }) { entry ->
                WatchCard(
                    entry = entry,
                    scanning = entry.watch.id in state.scanningIds,
                    onToggleActive = { viewModel.setActive(entry.watch.id, !entry.watch.isActive) },
                    onScanNow = { viewModel.scanNow(entry.watch.id) },
                    onEdit = { onEditWatch(entry.watch.id) },
                    onDelete = { pendingDeletion = entry },
                    onOpenLogin = onOpenLogin,
                )
            }
        }
    }

    pendingDeletion?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            containerColor = RadarColors.SurfaceElevated,
            titleContentColor = RadarColors.TextPrimary,
            textContentColor = RadarColors.TextSecondary,
            title = { Text("Supprimer cette veille ?") },
            text = {
                Text(
                    "« ${target.watch.name} » et les ${target.knownCount} annonces qu'elle a " +
                        "collectées seront supprimées définitivement."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.watch.id)
                    pendingDeletion = null
                }) {
                    Text("Supprimer", color = RadarColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text("Annuler", color = RadarColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun WatchCard(
    entry: WatchWithStats,
    scanning: Boolean,
    onToggleActive: () -> Unit,
    onScanNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val watch = entry.watch

    RdCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = watch.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = RadarColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = watch.criteriaSummary(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RadarColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiveDot(active = watch.isActive)
                    Text(
                        text = if (watch.isActive) "LIVE" else "PAUSE",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (watch.isActive) RadarColors.Live else RadarColors.TextTertiary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (watch.isUltra) {
                    BadgePill(text = "⚡ ULTRA")
                }
                BadgePill(
                    text = if (watch.isUltra) {
                        watch.frequency.description
                    } else {
                        Formatters.duration(watch.intervalSeconds)
                    },
                    contentColor = if (watch.isUltra) RadarColors.Accent else RadarColors.TextSecondary,
                    background = RadarColors.SurfaceElevated,
                )
                BadgePill(
                    text = "${entry.knownCount} ANNONCES",
                    contentColor = RadarColors.TextSecondary,
                    background = RadarColors.SurfaceElevated,
                )
                if (entry.newCount > 0) {
                    BadgePill(text = "${entry.newCount} NOUVEAU")
                }
            }

            Text(
                text = "Dernier scan : ${Formatters.relativeTime(watch.lastScanAt)}" +
                    if (watch.lastScanStatus == ScanStatus.NEVER) "" else " · ${watch.lastScanStatus.label}",
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.TextTertiary,
            )

            if (watch.lastScanStatus.isProblem) {
                StatusBanner(
                    status = watch.lastScanStatus,
                    action = if (watch.lastScanStatus == ScanStatus.NEEDS_LOGIN) {
                        {
                            SecondaryButton(
                                text = "Se connecter à Vinted",
                                onClick = onOpenLogin,
                                contentColor = RadarColors.Accent,
                            )
                        }
                    } else {
                        null
                    },
                )
            }

            // The four per-watch actions. The row scrolls horizontally so that a narrow
            // screen or a large font scale never clips "Supprimer" off the card.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(
                    text = if (watch.isActive) "Pause" else "Activer",
                    onClick = onToggleActive,
                    icon = if (watch.isActive) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                    contentColor = RadarColors.TextPrimary,
                )
                if (scanning) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = RadarColors.Accent,
                        )
                    }
                } else {
                    SecondaryButton(
                        text = "Scanner",
                        onClick = onScanNow,
                        icon = Icons.Rounded.Refresh,
                        contentColor = RadarColors.Accent,
                    )
                }
                SecondaryButton(
                    text = "Modifier",
                    onClick = onEdit,
                    icon = Icons.Rounded.Edit,
                    contentColor = RadarColors.TextPrimary,
                )
                SecondaryButton(
                    text = "Supprimer",
                    onClick = onDelete,
                    icon = Icons.Rounded.Delete,
                    contentColor = RadarColors.Danger,
                )
            }
        }
    }
}
