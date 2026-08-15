package com.radardeal.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radardeal.app.data.repository.ListingRepository
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.domain.model.DealRating
import com.radardeal.app.domain.model.Listing
import com.radardeal.app.domain.model.PricePoint
import com.radardeal.app.monitoring.DealEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ListingDetailUiState(
    val isLoading: Boolean = true,
    val listing: Listing? = null,
    val watchName: String = "",
    val rating: DealRating = DealRating.NONE,
    val discountLabel: String? = null,
    val comparableCount: Int = 0,
    val priceHistory: List<PricePoint> = emptyList(),
)

class ListingDetailViewModel(
    private val watchId: Long,
    private val itemId: String,
    private val listingRepository: ListingRepository,
    watchRepository: WatchRepository,
) : ViewModel() {

    val state: StateFlow<ListingDetailUiState> = combine(
        listingRepository.observeListing(watchId, itemId),
        listingRepository.observePricesForWatch(watchId),
        listingRepository.observePriceHistory(watchId, itemId),
        watchRepository.observeWatch(watchId),
    ) { listing, prices, history, watch ->
        val assessment = DealEngine.assess(listing?.price, prices)
        ListingDetailUiState(
            isLoading = false,
            listing = listing,
            watchName = watch?.name ?: "Veille supprimée",
            rating = assessment.rating,
            discountLabel = assessment.discount
                ?.takeIf { it > 0.0 && assessment.comparableCount >= DealEngine.MIN_COMPARABLES }
                ?.let { "${(it * 100).toInt()} % sous la médiane des annonces détectées" },
            comparableCount = assessment.comparableCount,
            priceHistory = history,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ListingDetailUiState(),
    )

    init {
        // Opening a listing is what marks it as seen.
        viewModelScope.launch {
            runCatching { listingRepository.clearNewFlag(watchId, itemId) }
        }
    }

    fun toggleFavorite() {
        val current = state.value.listing ?: return
        viewModelScope.launch {
            runCatching {
                listingRepository.setFavorite(watchId, itemId, !current.isFavorite)
            }
        }
    }
}
