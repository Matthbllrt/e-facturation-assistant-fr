package com.radardeal.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radardeal.app.data.prefs.AppSettings
import com.radardeal.app.data.prefs.OpenTarget
import com.radardeal.app.data.prefs.SettingsStore
import com.radardeal.app.data.repository.ListingRepository
import com.radardeal.app.domain.model.ScanFrequency
import com.radardeal.app.notifications.RadarNotifications
import com.radardeal.app.web.VintedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val hasSession: Boolean = false,
)

class SettingsViewModel(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val listingRepository: ListingRepository,
    private val session: VintedSession,
    private val notifications: RadarNotifications,
) : ViewModel() {

    /** Re-read after the user signs in or out, since cookies are not an observable source. */
    private val sessionTick = MutableStateFlow(0)

    val state: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        sessionTick,
    ) { settings, _ ->
        SettingsUiState(
            settings = settings,
            hasSession = runCatching { session.hasSession(settings.vintedHost) }.getOrDefault(false),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setNotificationsEnabled(value: Boolean) = update {
        settingsStore.setNotificationsEnabled(value)
    }

    fun setSoundEnabled(value: Boolean) = update { settingsStore.setSoundEnabled(value) }

    fun setVibrationEnabled(value: Boolean) = update { settingsStore.setVibrationEnabled(value) }

    fun setDefaultFrequency(frequency: ScanFrequency) = update {
        settingsStore.setDefaultInterval(frequency.seconds)
    }

    fun setOpenTarget(target: OpenTarget) = update { settingsStore.setOpenTarget(target) }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { listingRepository.clearHistoryKeepingFavorites() }
            }
            runCatching { notifications.cancelAll() }
        }
    }

    fun signOutOfVinted() {
        viewModelScope.launch {
            runCatching {
                session.clearCookies { sessionTick.value++ }
                settingsStore.setSessionEstablished(false)
            }
            sessionTick.value++
        }
    }

    fun refreshSessionState() {
        sessionTick.value++
    }

    /**
     * Every write also refreshes the notification channels, so a change to sound or vibration
     * takes effect on the very next alert instead of after a restart.
     */
    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
            runCatching {
                val current = state.value.settings
                notifications.ensureChannels(current)
            }
        }
    }
}
