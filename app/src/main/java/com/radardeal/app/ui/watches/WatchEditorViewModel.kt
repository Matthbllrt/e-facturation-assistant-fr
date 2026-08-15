package com.radardeal.app.ui.watches

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radardeal.app.data.prefs.SettingsStore
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.domain.model.ScanFrequency
import com.radardeal.app.domain.model.Watch
import com.radardeal.app.monitoring.RadarCoordinator
import com.radardeal.app.monitoring.VintedQuery
import com.radardeal.app.service.MonitoringController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which of the two ways of defining a search the user picked. */
enum class WatchMode { CRITERIA, URL }

data class WatchEditorUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val mode: WatchMode = WatchMode.CRITERIA,
    val name: String = "",
    val keyword: String = "",
    val brand: String = "",
    val maxPrice: String = "",
    val sourceUrl: String = "",
    val frequency: ScanFrequency = ScanFrequency.FAST,
    val urlError: String? = null,
    val saved: Boolean = false,
) {
    /** The name field is optional: a sensible one is derived from the criteria when left blank. */
    val effectiveName: String
        get() = name.trim().ifBlank {
            when (mode) {
                WatchMode.CRITERIA -> listOf(brand.trim(), keyword.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                    .ifBlank { "Ma veille" }

                WatchMode.URL -> "Recherche Vinted"
            }
        }

    val canSave: Boolean
        get() = when (mode) {
            WatchMode.CRITERIA -> keyword.isNotBlank() || brand.isNotBlank()
            WatchMode.URL -> sourceUrl.isNotBlank() && urlError == null
        }
}

class WatchEditorViewModel(
    private val appContext: Context,
    private val watchId: Long?,
    private val watchRepository: WatchRepository,
    private val coordinator: RadarCoordinator,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(WatchEditorUiState())
    val state: StateFlow<WatchEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val defaultInterval = runCatching { settingsStore.settings.first().defaultIntervalSeconds }
                .getOrDefault(ScanFrequency.FAST.seconds)

            val existing = watchId?.let { runCatching { watchRepository.getWatch(it) }.getOrNull() }

            _state.value = if (existing == null) {
                WatchEditorUiState(
                    isLoading = false,
                    frequency = ScanFrequency.fromSeconds(defaultInterval),
                )
            } else {
                WatchEditorUiState(
                    isLoading = false,
                    isEditing = true,
                    mode = if (existing.isUrlBased) WatchMode.URL else WatchMode.CRITERIA,
                    name = existing.name,
                    keyword = existing.keyword.orEmpty(),
                    brand = existing.brand.orEmpty(),
                    maxPrice = existing.maxPrice?.let { formatPrice(it) }.orEmpty(),
                    sourceUrl = existing.sourceUrl.orEmpty(),
                    frequency = ScanFrequency.fromSeconds(existing.intervalSeconds),
                )
            }
        }
    }

    fun setMode(mode: WatchMode) = _state.update { it.copy(mode = mode) }
    fun setName(value: String) = _state.update { it.copy(name = value) }
    fun setKeyword(value: String) = _state.update { it.copy(keyword = value) }
    fun setBrand(value: String) = _state.update { it.copy(brand = value) }
    fun setFrequency(value: ScanFrequency) = _state.update { it.copy(frequency = value) }

    /** Digits and one separator only — the field must never accept something unparseable. */
    fun setMaxPrice(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }.take(9)
        _state.update { it.copy(maxPrice = filtered) }
    }

    /** Validated as the user types, so the error appears next to the field, not on save. */
    fun setSourceUrl(value: String) {
        val trimmed = value.trim()
        val error = if (trimmed.isBlank()) {
            null
        } else {
            (VintedQuery.checkSearchUrl(trimmed) as? VintedQuery.UrlCheck.Invalid)?.reason
        }
        _state.update { it.copy(sourceUrl = value, urlError = error) }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return

        viewModelScope.launch {
            val existing = watchId?.let { runCatching { watchRepository.getWatch(it) }.getOrNull() }
            val urlBased = current.mode == WatchMode.URL

            val watch = Watch(
                id = existing?.id ?: 0L,
                name = current.effectiveName,
                keyword = if (urlBased) null else current.keyword.trim().ifBlank { null },
                brand = if (urlBased) null else current.brand.trim().ifBlank { null },
                maxPrice = if (urlBased) null else parsePrice(current.maxPrice),
                sourceUrl = if (urlBased) current.sourceUrl.trim().ifBlank { null } else null,
                intervalSeconds = current.frequency.seconds,
                isActive = existing?.isActive ?: true,
                notifyEnabled = existing?.notifyEnabled ?: true,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastScanAt = existing?.lastScanAt,
                lastScanStatus = existing?.lastScanStatus ?: com.radardeal.app.domain.model.ScanStatus.NEVER,
                baselineDone = existing?.baselineDone ?: false,
            )

            val savedId = runCatching {
                if (existing == null) {
                    watchRepository.create(watch)
                } else {
                    watchRepository.update(watch)
                    // The criteria may have changed, in which case the listings collected under
                    // the old query are no longer a valid baseline for the new one.
                    if (criteriaChanged(existing, watch)) {
                        watchRepository.resetBaseline(existing.id)
                    }
                    existing.id
                }
            }.getOrNull()

            if (savedId != null) {
                coordinator.markDueNow(savedId)
                // Creating a watch is a clear signal that the user wants the radar running.
                runCatching { settingsStore.setMonitoringRequested(true) }
                MonitoringController.start(appContext)
            }

            _state.update { it.copy(saved = true) }
        }
    }

    private fun criteriaChanged(before: Watch, after: Watch): Boolean =
        before.keyword != after.keyword ||
            before.brand != after.brand ||
            before.maxPrice != after.maxPrice ||
            before.sourceUrl != after.sourceUrl

    private fun parsePrice(raw: String): Double? =
        raw.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0.0 }

    private fun formatPrice(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun MutableStateFlow<WatchEditorUiState>.update(
        block: (WatchEditorUiState) -> WatchEditorUiState,
    ) {
        value = block(value)
    }
}
