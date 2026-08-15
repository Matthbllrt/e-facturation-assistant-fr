package com.radardeal.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radardeal.app.data.prefs.SettingsStore
import kotlinx.coroutines.launch

class OnboardingViewModel(private val settingsStore: SettingsStore) : ViewModel() {

    fun completeOnboarding() {
        viewModelScope.launch { settingsStore.setOnboardingDone(true) }
    }
}
