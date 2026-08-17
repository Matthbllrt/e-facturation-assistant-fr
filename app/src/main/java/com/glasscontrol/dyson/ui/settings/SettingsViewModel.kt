package com.glasscontrol.dyson.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.data.store.AppPrefs
import com.glasscontrol.dyson.data.store.AppPrefsStore
import com.glasscontrol.dyson.ui.theme.AppTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val store: AppPrefsStore) : ViewModel() {

    val prefs: StateFlow<AppPrefs> =
        store.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPrefs())

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { store.setTheme(theme) }
    }

    fun setBackgroundRefresh(enabled: Boolean) {
        viewModelScope.launch { store.setBackgroundRefresh(enabled) }
    }

    fun reconnect() {
        viewModelScope.launch { DysonServices.repository.refreshState() }
    }

    /** Wipes the credential from the Keystore and forgets the machine. */
    fun forgetDevice(onDone: () -> Unit) {
        viewModelScope.launch {
            DysonServices.repository.forgetDevice()
            onDone()
        }
    }
}
