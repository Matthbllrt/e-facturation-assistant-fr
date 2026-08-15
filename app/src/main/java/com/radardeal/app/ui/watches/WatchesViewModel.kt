package com.radardeal.app.ui.watches

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radardeal.app.data.prefs.SettingsStore
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.data.repository.WatchWithStats
import com.radardeal.app.monitoring.RadarCoordinator
import com.radardeal.app.service.MonitoringController
import com.radardeal.app.service.RadarService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WatchesUiState(
    val isLoading: Boolean = true,
    val watches: List<WatchWithStats> = emptyList(),
    val monitoringRunning: Boolean = false,
    /** Ids currently being scanned on demand, so their card can show a spinner. */
    val scanningIds: Set<Long> = emptySet(),
)

class WatchesViewModel(
    private val appContext: Context,
    private val watchRepository: WatchRepository,
    private val coordinator: RadarCoordinator,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val scanningIds = MutableStateFlow<Set<Long>>(emptySet())

    /** One-shot message for the snackbar, e.g. the result of a manual scan. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<WatchesUiState> = combine(
        watchRepository.observeWatchesWithStats(),
        RadarService.isRunning,
        scanningIds,
    ) { watches, running, scanning ->
        WatchesUiState(
            isLoading = false,
            watches = watches,
            monitoringRunning = running,
            scanningIds = scanning,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WatchesUiState(),
    )

    fun setActive(watchId: Long, active: Boolean) {
        viewModelScope.launch {
            runCatching {
                watchRepository.setActive(watchId, active)
                if (active) {
                    coordinator.markDueNow(watchId)
                    // Resuming a watch is also the natural moment to make sure the radar runs.
                    if (!RadarService.isRunning.value) {
                        settingsStore.setMonitoringRequested(true)
                        MonitoringController.start(appContext)
                    }
                }
            }
        }
    }

    fun scanNow(watchId: Long) {
        viewModelScope.launch {
            scanningIds.value = scanningIds.value + watchId
            try {
                val report = coordinator.scanNow(watchId)
                _message.value = when {
                    report == null -> "Cette veille est introuvable."
                    report.status.isProblem -> report.status.label
                    report.wasBaseline ->
                        "Référence enregistrée : ${report.parsedCount} annonces. " +
                            "Les prochaines nouveautés seront signalées."
                    report.newCount > 0 ->
                        "${report.newCount} nouvelle${if (report.newCount > 1) "s" else ""} annonce" +
                            if (report.newCount > 1) "s" else ""
                    report.parsedCount == 0 -> "Aucun résultat pour cette recherche."
                    else -> "Aucune nouveauté (${report.parsedCount} annonces analysées)."
                }
            } finally {
                scanningIds.value = scanningIds.value - watchId
            }
        }
    }

    fun delete(watchId: Long) {
        viewModelScope.launch {
            runCatching {
                watchRepository.delete(watchId)
                coordinator.forget(watchId)
            }
            _message.value = "Veille supprimée."
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
