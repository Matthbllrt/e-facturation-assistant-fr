package com.radardeal.app.ui.radar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radardeal.app.data.prefs.AppSettings
import com.radardeal.app.data.prefs.SettingsStore
import com.radardeal.app.data.repository.ListingRepository
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.domain.model.DealRating
import com.radardeal.app.domain.model.Listing
import com.radardeal.app.domain.model.ListingCard
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.monitoring.DealEngine
import com.radardeal.app.monitoring.RadarCoordinator
import com.radardeal.app.service.MonitoringController
import com.radardeal.app.service.RadarService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/** The chips above the feed. */
enum class RadarFilter(val label: String) {
    ALL("Tout"),
    NEW("Nouveau"),
    DEALS("Deals"),
    PRICE_DROP("Prix ↓"),
    FAVORITES("Favoris"),
}

data class RadarUiState(
    val isLoading: Boolean = true,
    val greeting: String = "Bonjour",
    val watchCount: Int = 0,
    val activeWatchCount: Int = 0,
    val newCount: Int = 0,
    val priceDropCount: Int = 0,
    val cards: List<ListingCard> = emptyList(),
    val filter: RadarFilter = RadarFilter.ALL,
    /** The most actionable problem across all watches, if any. */
    val problem: ScanStatus? = null,
    val monitoringRunning: Boolean = false,
    val isScanning: Boolean = false,
    /** Name of the watch holding the single Ultra slot, when one does. */
    val ultraWatchName: String? = null,
    val settings: AppSettings = AppSettings(),
) {
    val hasWatches: Boolean get() = watchCount > 0
}

class RadarViewModel(
    private val appContext: Context,
    private val watchRepository: WatchRepository,
    private val listingRepository: ListingRepository,
    private val coordinator: RadarCoordinator,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val filter = MutableStateFlow(RadarFilter.ALL)

    /**
     * A window over the most recent listings. It is deliberately wider than what the feed
     * shows: the same set feeds the per-watch median, and a median needs more than the visible
     * page to mean anything.
     */
    private val listings = listingRepository.observeRecent(FEED_WINDOW)

    private val core = combine(
        watchRepository.observeWatchesWithStats(),
        listings,
        filter,
        settingsStore.settings,
    ) { watches, allListings, currentFilter, settings ->
        val watchNames = watches.associate { it.watch.id to it.watch.name }
        val pricesByWatch = allListings
            .groupBy { it.watchId }
            .mapValues { (_, group) -> group.mapNotNull { it.price } }

        val cards = allListings.map { listing ->
            val assessment = DealEngine.assess(
                price = listing.price,
                allPrices = pricesByWatch[listing.watchId].orEmpty(),
            )
            ListingCard(
                listing = listing,
                watchName = watchNames[listing.watchId] ?: "Veille supprimée",
                rating = assessment.rating,
                medianDiscount = assessment.discount,
                comparableCount = assessment.comparableCount,
            )
        }

        RadarUiState(
            isLoading = false,
            greeting = greeting(),
            watchCount = watches.size,
            activeWatchCount = watches.count { it.watch.isActive },
            newCount = allListings.count { it.isNew },
            priceDropCount = allListings.count { it.hasPriceDrop },
            cards = applyFilter(cards, currentFilter),
            filter = currentFilter,
            problem = watches
                .mapNotNull { it.watch.lastScanStatus.takeIf { status -> status.isProblem } }
                .minByOrNull { it.priority() },
            ultraWatchName = watches.firstOrNull { it.watch.isUltra && it.watch.isActive }
                ?.watch?.name,
            settings = settings,
        )
    }

    val state: StateFlow<RadarUiState> = combine(
        core,
        RadarService.isRunning,
        coordinator.isScanning,
    ) { base, running, scanning ->
        base.copy(monitoringRunning = running, isScanning = scanning)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RadarUiState(),
    )

    // --- Actions -----------------------------------------------------------------------------

    fun setFilter(value: RadarFilter) {
        filter.value = value
    }

    fun toggleFavorite(listing: Listing) {
        viewModelScope.launch {
            runCatching {
                listingRepository.setFavorite(listing.watchId, listing.itemId, !listing.isFavorite)
            }
        }
    }

    /** Opening a listing clears its NEW badge. */
    fun markSeen(watchId: Long, itemId: String) {
        viewModelScope.launch {
            runCatching { listingRepository.clearNewFlag(watchId, itemId) }
        }
    }

    fun clearAllNewFlags() {
        viewModelScope.launch { runCatching { listingRepository.clearAllNewFlags() } }
    }

    /** Pull-to-refresh: scan every active watch immediately, ignoring their schedule. */
    fun refreshNow() {
        viewModelScope.launch {
            runCatching {
                watchRepository.getActive().forEach { coordinator.markDueNow(it.id) }
                coordinator.tick()
            }
        }
    }

    fun startMonitoring() {
        viewModelScope.launch {
            runCatching { settingsStore.setMonitoringRequested(true) }
            MonitoringController.start(appContext)
        }
    }

    fun stopMonitoring() {
        viewModelScope.launch {
            runCatching { settingsStore.setMonitoringRequested(false) }
            MonitoringController.stop(appContext)
        }
    }

    // --- Helpers -----------------------------------------------------------------------------

    private fun applyFilter(cards: List<ListingCard>, filter: RadarFilter): List<ListingCard> =
        when (filter) {
            RadarFilter.ALL -> cards
            RadarFilter.NEW -> cards.filter { it.listing.isNew }
            RadarFilter.DEALS -> cards
                .filter { it.rating != DealRating.NONE }
                .sortedByDescending { it.medianDiscount ?: 0.0 }
            RadarFilter.PRICE_DROP -> cards
                .filter { it.listing.hasPriceDrop }
                .sortedByDescending { it.listing.priceDroppedAt ?: 0L }
            RadarFilter.FAVORITES -> cards.filter { it.listing.isFavorite }
        }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (hour in 5..17) "Bonjour" else "Bonsoir"
    }

    /** Lower is more urgent — decides which banner to show when several watches are unhappy. */
    private fun ScanStatus.priority(): Int = when (this) {
        ScanStatus.NEEDS_LOGIN -> 0
        ScanStatus.NEEDS_VERIFICATION -> 1
        ScanStatus.RATE_LIMITED -> 2
        ScanStatus.NETWORK_ERROR -> 3
        ScanStatus.PARSE_ERROR -> 4
        else -> 99
    }

    private companion object {
        const val FEED_WINDOW = 600
    }
}
