package com.glasscontrol.dyson.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.data.local.CommandEncoder
import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.domain.model.DysonCommand
import com.glasscontrol.dyson.domain.model.DysonDevice
import com.glasscontrol.dyson.domain.model.DysonState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DysonUiState(
    val device: DysonDevice? = null,
    val state: DysonState = DysonState(),
    val capabilities: DysonCapabilities = DysonCapabilities(),
) {
    val configured: Boolean get() = device != null
}

/**
 * Shared view model for the control surfaces.
 *
 * It holds no state of its own beyond a transient error: the repository's cache
 * is the single source of truth, so the app and the widget can never disagree.
 */
class DysonViewModel : ViewModel() {

    private val repository = DysonServices.repository

    val uiState: StateFlow<DysonUiState> = combine(
        repository.device,
        repository.state,
        repository.capabilities,
    ) { device, state, capabilities ->
        DysonUiState(device, state, capabilities)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DysonUiState())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Opens a live session so the screen tracks the machine in real time. */
    fun startLiveUpdates() {
        viewModelScope.launch {
            repository.connect().onFailure { _error.value = it.message }
        }
    }

    fun stopLiveUpdates() {
        viewModelScope.launch { repository.disconnect() }
    }

    fun refresh() = run { repository.refreshState() }

    fun togglePower() = run { repository.setPower(!uiState.value.state.power) }

    fun toggleAuto() = run { repository.setAutoMode(!uiState.value.state.autoMode) }

    fun toggleOscillation() = run { repository.setOscillation(!uiState.value.state.oscillation) }

    fun toggleNight() = run { repository.setNightMode(!uiState.value.state.nightMode) }

    fun toggleHeating() = run { repository.setHeating(!uiState.value.state.heating) }

    fun stepSpeed(delta: Int) = run { repository.execute(DysonCommand.StepFanSpeed(delta)) }

    fun setSpeed(speed: Int) = run { repository.setFanSpeed(speed) }

    fun setTemperature(celsius: Float) =
        run { repository.setTemperature(CommandEncoder.clampTemperature(celsius)) }

    fun setAirflowFront(front: Boolean) = run { repository.setAirflowDirection(front) }

    fun dismissError() {
        _error.value = null
    }

    /** Runs a repository call, surfacing only the failure to the UI. */
    private fun run(block: suspend () -> Result<*>) {
        viewModelScope.launch {
            block().onFailure { _error.value = it.message }
        }
    }
}
