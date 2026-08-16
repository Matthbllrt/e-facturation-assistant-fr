package com.radardeal.app.ui.radar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radardeal.app.core.RdLog
import com.radardeal.app.data.prefs.OpenTarget
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.ui.components.BadgePill
import com.radardeal.app.ui.components.EmptyState
import com.radardeal.app.ui.components.ListingCardLarge
import com.radardeal.app.ui.components.LiveDot
import com.radardeal.app.ui.components.PrimaryButton
import com.radardeal.app.ui.components.RdFilterChip
import com.radardeal.app.ui.components.SecondaryButton
import com.radardeal.app.ui.components.SectionHeader
import com.radardeal.app.ui.components.StatTile
import com.radardeal.app.ui.components.StatusBanner
import com.radardeal.app.ui.radarViewModel
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors

/**
 * The home screen: greeting, radar state, three counters, filter chips and the feed of
 * opportunities. One vertical scroll — no tabs inside tabs, no hidden drawers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    onOpenListing: (Long, String) -> Unit,
    onCreateWatch: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenWatches: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val viewModel = radarViewModel { graph ->
        RadarViewModel(
            appContext = graph.appContext,
            watchRepository = graph.watchRepository,
            listingRepository = graph.listingRepository,
            coordinator = graph.coordinator,
            settingsStore = graph.settingsStore,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    RequestNotificationPermissionOnce()

    PullToRefreshBox(
        isRefreshing = state.isScanning,
        onRefresh = viewModel::refreshNow,
        modifier = Modifier
            .fillMaxSize()
            .background(RadarColors.Background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                bottom = spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item(key = "header") {
                RadarHeader(
                    greeting = state.greeting,
                    activeWatches = state.activeWatchCount,
                    running = state.monitoringRunning,
                    ultraWatchName = state.ultraWatchName,
                    modifier = Modifier.statusBarsPadding(),
                )
            }

            item(key = "stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                    StatTile(
                        value = state.newCount.toString(),
                        label = "Nouvelles annonces",
                        accent = if (state.newCount > 0) RadarColors.Accent else RadarColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = state.activeWatchCount.toString(),
                        label = "Veilles actives",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = state.priceDropCount.toString(),
                        label = "Baisses de prix",
                        accent = if (state.priceDropCount > 0) RadarColors.PriceDrop else RadarColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            state.problem?.let { problem ->
                item(key = "problem") {
                    StatusBanner(
                        status = problem,
                        action = if (problem == ScanStatus.NEEDS_LOGIN) {
                            {
                                SecondaryButton(
                                    text = "Se connecter à Vinted",
                                    onClick = onOpenLogin,
                                    icon = Icons.Rounded.Login,
                                    contentColor = RadarColors.Accent,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            if (state.hasWatches) {
                item(key = "monitoring") {
                    MonitoringToggle(
                        running = state.monitoringRunning,
                        activeWatches = state.activeWatchCount,
                        onStart = viewModel::startMonitoring,
                        onStop = viewModel::stopMonitoring,
                    )
                }

                item(key = "chips") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        items(RadarFilter.entries.toList(), key = { it.name }) { filter ->
                            RdFilterChip(
                                label = filter.label,
                                selected = state.filter == filter,
                                onClick = { viewModel.setFilter(filter) },
                            )
                        }
                    }
                }

                item(key = "section") {
                    SectionHeader(
                        title = "Dernières opportunités",
                        trailing = {
                            if (state.newCount > 0) {
                                Text(
                                    text = "Tout marquer comme vu",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RadarColors.Accent,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .clickableNoRipple { viewModel.clearAllNewFlags() },
                                )
                            }
                        },
                    )
                }
            }

            when {
                !state.hasWatches && !state.isLoading -> item(key = "empty-watches") {
                    EmptyState(
                        icon = Icons.Rounded.Radar,
                        title = "Aucune veille",
                        message = "Crée ton premier radar pour surveiller une recherche Vinted " +
                            "et être alerté dès qu'une annonce apparaît.",
                        action = {
                            PrimaryButton(text = "Créer un radar", onClick = onCreateWatch)
                        },
                    )
                }

                state.cards.isEmpty() && !state.isLoading -> item(key = "empty-feed") {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = emptyTitleFor(state.filter),
                        message = emptyMessageFor(state.filter),
                        action = if (state.filter != RadarFilter.ALL) {
                            {
                                SecondaryButton(
                                    text = "Voir tout",
                                    onClick = { viewModel.setFilter(RadarFilter.ALL) },
                                )
                            }
                        } else {
                            {
                                SecondaryButton(text = "Gérer mes veilles", onClick = onOpenWatches)
                            }
                        },
                    )
                }

                else -> items(
                    items = state.cards,
                    key = { "${it.listing.watchId}:${it.listing.itemId}" },
                ) { card ->
                    ListingCardLarge(
                        card = card,
                        onOpen = {
                            viewModel.markSeen(card.listing.watchId, card.listing.itemId)
                            onOpenListing(card.listing.watchId, card.listing.itemId)
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(card.listing) },
                        onOpenOnVinted = {
                            viewModel.markSeen(card.listing.watchId, card.listing.itemId)
                            if (state.settings.openTarget == OpenTarget.EXTERNAL) {
                                openExternally(context, card.listing.itemUrl)
                            } else {
                                onOpenListing(card.listing.watchId, card.listing.itemId)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarHeader(
    greeting: String,
    activeWatches: Int,
    running: Boolean,
    ultraWatchName: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.xl, bottom = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = "$greeting 👋",
            style = MaterialTheme.typography.displayLarge,
            color = RadarColors.TextPrimary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            LiveDot(active = running)
            Text(
                text = when {
                    running && ultraWatchName != null -> "RADAR ULTRA ACTIF"
                    running -> "Radar actif"
                    else -> "Radar en pause"
                },
                style = MaterialTheme.typography.titleLarge,
                color = if (running) RadarColors.Live else RadarColors.TextSecondary,
            )
        }
        Text(
            text = when {
                ultraWatchName != null && running -> "⚡ $ultraWatchName · balayage continu"
                activeWatches == 0 -> "Aucune recherche surveillée"
                activeWatches == 1 -> "1 recherche surveillée"
                else -> "$activeWatches recherches surveillées"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = RadarColors.TextSecondary,
        )
    }
}

@Composable
private fun MonitoringToggle(
    running: Boolean,
    activeWatches: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val spacing = LocalSpacing.current
    com.radardeal.app.ui.components.RdCard(
        modifier = Modifier.fillMaxWidth(),
        color = if (running) RadarColors.AccentSoft else RadarColors.Surface,
        border = null,
    ) {
        Row(
            modifier = Modifier.padding(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = if (running) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                contentDescription = null,
                tint = RadarColors.Accent,
                modifier = Modifier.size(30.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) "Surveillance en cours" else "Surveillance arrêtée",
                    style = MaterialTheme.typography.titleLarge,
                    color = RadarColors.TextPrimary,
                )
                Text(
                    text = if (running) {
                        "RadarDeal scanne en arrière-plan. Une notification permanente le rappelle."
                    } else {
                        "Lance la surveillance pour recevoir les nouvelles annonces."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = RadarColors.TextSecondary,
                )
            }
            SecondaryButton(
                text = if (running) "Arrêter" else "Démarrer",
                onClick = if (running) onStop else onStart,
                enabled = running || activeWatches > 0,
                contentColor = if (running) RadarColors.Danger else RadarColors.Accent,
            )
        }
    }
}

/**
 * Android 13+ needs an explicit grant before any alert can be posted. Asked once, on the first
 * visit to the radar — never on the splash, and never repeatedly.
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        RdLog.d("Radar", "notification permission granted=$granted")
    }

    LaunchedEffect(Unit) {
        runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

private fun emptyTitleFor(filter: RadarFilter): String = when (filter) {
    RadarFilter.ALL -> "Aucune annonce pour le moment"
    RadarFilter.NEW -> "Aucune nouvelle annonce"
    RadarFilter.DEALS -> "Aucun deal détecté"
    RadarFilter.PRICE_DROP -> "Aucune baisse de prix"
    RadarFilter.FAVORITES -> "Aucun favori"
}

private fun emptyMessageFor(filter: RadarFilter): String = when (filter) {
    RadarFilter.ALL ->
        "Dès que le radar trouvera des annonces correspondant à tes veilles, elles apparaîtront ici."
    RadarFilter.NEW ->
        "Le premier scan d'une veille sert de référence : seules les annonces publiées ensuite " +
            "sont marquées comme nouvelles."
    RadarFilter.DEALS ->
        "RadarDeal compare les prix à la médiane des annonces détectées. Il lui faut au moins " +
            "${com.radardeal.app.monitoring.DealEngine.MIN_COMPARABLES} annonces avec un prix pour se prononcer."
    RadarFilter.PRICE_DROP ->
        "Aucune annonce suivie n'a baissé de prix depuis son premier scan."
    RadarFilter.FAVORITES ->
        "Touche le cœur sur une annonce pour la garder ici."
}

/** Opens a listing in the user's browser or in the Vinted app if it is installed. */
internal fun openExternally(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { RdLog.w("Radar", "no app could open the listing") }
}

/** Text acting as a link: tappable without the ripple that would look wrong on a label. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(indication = null, interactionSource = null, onClick = onClick)
