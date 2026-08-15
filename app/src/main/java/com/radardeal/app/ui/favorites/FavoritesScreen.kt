package com.radardeal.app.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.radardeal.app.data.repository.ListingRepository
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.domain.model.Listing
import com.radardeal.app.domain.model.ListingCard
import com.radardeal.app.ui.components.EmptyState
import com.radardeal.app.ui.components.ListingRowCompact
import com.radardeal.app.ui.radarViewModel
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FavoritesUiState(
    val isLoading: Boolean = true,
    val cards: List<ListingCard> = emptyList(),
)

class FavoritesViewModel(
    private val listingRepository: ListingRepository,
    watchRepository: WatchRepository,
) : ViewModel() {

    val state: StateFlow<FavoritesUiState> = combine(
        listingRepository.observeFavorites(),
        watchRepository.observeWatches(),
    ) { favorites, watches ->
        val names = watches.associate { it.id to it.name }
        FavoritesUiState(
            isLoading = false,
            cards = favorites.map { listing ->
                ListingCard(
                    listing = listing,
                    watchName = names[listing.watchId] ?: "Veille supprimée",
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FavoritesUiState(),
    )

    fun toggleFavorite(listing: Listing) {
        viewModelScope.launch {
            runCatching {
                listingRepository.setFavorite(listing.watchId, listing.itemId, !listing.isFavorite)
            }
        }
    }
}

/** Saved listings. Favourites survive history clearing and per-watch trimming. */
@Composable
fun FavoritesScreen(onOpenListing: (Long, String) -> Unit) {
    val spacing = LocalSpacing.current
    val viewModel = radarViewModel { graph ->
        FavoritesViewModel(graph.listingRepository, graph.watchRepository)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

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
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = spacing.xl, bottom = spacing.sm),
            ) {
                Text(
                    text = "Favoris",
                    style = MaterialTheme.typography.displayMedium,
                    color = RadarColors.TextPrimary,
                )
                Text(
                    text = when (state.cards.size) {
                        0 -> "Aucune annonce enregistrée"
                        1 -> "1 annonce enregistrée"
                        else -> "${state.cards.size} annonces enregistrées"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = RadarColors.TextSecondary,
                )
            }
        }

        if (state.cards.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = "Aucun favori",
                    message = "Touche le cœur sur une annonce pour la retrouver ici. " +
                        "Les favoris sont conservés même quand l'historique est effacé.",
                )
            }
        }

        items(state.cards, key = { "${it.listing.watchId}:${it.listing.itemId}" }) { card ->
            ListingRowCompact(
                card = card,
                onOpen = { onOpenListing(card.listing.watchId, card.listing.itemId) },
                onToggleFavorite = { viewModel.toggleFavorite(card.listing) },
            )
        }
    }
}
