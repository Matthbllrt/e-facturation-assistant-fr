package com.glasscontrol.dyson.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.data.cloud.DysonAccountToken
import com.glasscontrol.dyson.data.cloud.DysonCrypto
import com.glasscontrol.dyson.data.cloud.LoginChallenge
import com.glasscontrol.dyson.domain.DiscoveredDevice
import com.glasscontrol.dyson.domain.model.DysonDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the user is in setup. */
enum class OnboardingStep {
    WELCOME,
    METHOD,
    CLOUD_EMAIL,
    CLOUD_CODE,
    CLOUD_DEVICES,
    MANUAL,
    DONE,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val busy: Boolean = false,
    val error: String? = null,
    val email: String = "",
    val region: String = "FR",
    val otpCode: String = "",
    val password: String = "",
    val devices: List<DysonDevice> = emptyList(),
    // Manual entry
    val manualHost: String = "",
    val manualSerial: String = "",
    val manualDeviceType: String = "",
    val manualCredential: String = "",
    val manualWifiPassword: String = "",
    val useWifiPassword: Boolean = true,
    val discovered: List<DiscoveredDevice> = emptyList(),
)

/**
 * Drives account setup and manual configuration.
 *
 * The MyDyson password only ever lives in this state while the verification call
 * is in flight, and is cleared the moment a token comes back.
 */
class OnboardingViewModel : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var challenge: LoginChallenge? = null
    private var token: DysonAccountToken? = null

    fun goTo(step: OnboardingStep) = _state.update { it.copy(step = step, error = null) }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value.trim()) }
    fun onRegionChange(value: String) = _state.update { it.copy(region = value.uppercase().take(2)) }
    fun onOtpChange(value: String) = _state.update { it.copy(otpCode = value.trim()) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }
    fun onManualHostChange(value: String) = _state.update { it.copy(manualHost = value.trim()) }
    /**
     * Accepts either a bare serial or the machine's full Wi-Fi SSID.
     *
     * The sticker shows `DYSON-NN2-EU-ABC1234A-438` next to the Wi-Fi password,
     * so pasting it fills in the device type too.
     */
    fun onManualSerialChange(value: String) {
        val entered = value.trim().uppercase()
        val parsed = DysonCrypto.parseWifiSsid(entered)
        _state.update {
            if (parsed != null) {
                it.copy(manualSerial = parsed.first, manualDeviceType = parsed.second)
            } else {
                it.copy(manualSerial = entered)
            }
        }
    }
    fun onManualTypeChange(value: String) = _state.update { it.copy(manualDeviceType = value.trim().uppercase()) }
    fun onManualCredentialChange(value: String) = _state.update { it.copy(manualCredential = value.trim()) }
    fun onWifiPasswordChange(value: String) = _state.update { it.copy(manualWifiPassword = value) }
    fun onUseWifiPasswordChange(value: Boolean) = _state.update { it.copy(useWifiPassword = value) }
    fun dismissError() = _state.update { it.copy(error = null) }

    /** Requests the emailed one-time code. */
    fun requestCode() {
        val current = _state.value
        if (current.email.isBlank()) {
            _state.update { it.copy(error = "Saisissez votre adresse e-mail") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            DysonServices.cloudApi.startLogin(current.email, current.region)
                .onSuccess { result ->
                    challenge = result
                    _state.update { it.copy(busy = false, step = OnboardingStep.CLOUD_CODE) }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.message) }
                }
        }
    }

    /** Verifies the code, then lists the machines on the account. */
    fun verifyCode() {
        val pending = challenge ?: return
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            DysonServices.cloudApi.verifyLogin(pending, current.otpCode, current.password)
                .onSuccess { accountToken ->
                    token = accountToken
                    // The account password has done its job; drop it immediately.
                    _state.update { it.copy(password = "") }
                    loadDevices()
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.message) }
                }
        }
    }

    private suspend fun loadDevices() {
        val accountToken = token ?: return
        DysonServices.cloudApi.listDevices(accountToken)
            .onSuccess { devices ->
                _state.update {
                    it.copy(
                        busy = false,
                        devices = devices,
                        step = OnboardingStep.CLOUD_DEVICES,
                        error = if (devices.isEmpty()) {
                            "Aucun appareil contrôlable trouvé sur ce compte"
                        } else {
                            null
                        },
                    )
                }
            }
            .onFailure { error ->
                _state.update { it.copy(busy = false, error = error.message) }
            }
    }

    /**
     * Saves a machine picked from the account and locates it on the network.
     *
     * Discovery is best-effort: a machine that is asleep will be found later by
     * the repository's own retry, so setup does not block on it.
     */
    fun selectCloudDevice(device: DysonDevice, onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val host = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                DysonServices.repository.discoverDevices()
                    .firstMatching(device.serial)
            }
            val saved = runCatching {
                DysonServices.repository.saveDevice(device.copy(host = host))
            }
            saved.onFailure { error ->
                _state.update { it.copy(busy = false, error = error.message) }
                return@launch
            }

            DysonServices.repository.refreshState()
            _state.update {
                it.copy(
                    busy = false,
                    step = OnboardingStep.DONE,
                    // Not fatal: the machine may simply be asleep, and Device
                    // lets the address be entered by hand.
                    error = if (host == null) {
                        "Appareil enregistré, mais introuvable sur le Wi-Fi pour l'instant. " +
                            "Vous pourrez saisir son adresse IP dans l'onglet Appareil."
                    } else {
                        null
                    },
                )
            }
            onDone()
        }
    }

    /** Runs an mDNS sweep to help the user fill the manual form. */
    fun scanNetwork() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, discovered = emptyList(), error = null) }
            val found = mutableListOf<DiscoveredDevice>()
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                DysonServices.repository.discoverDevices().collect { device ->
                    if (found.none { it.serial == device.serial }) {
                        found += device
                        _state.update { it.copy(discovered = found.toList()) }
                    }
                }
            }
            _state.update {
                it.copy(
                    busy = false,
                    error = if (found.isEmpty()) "Aucun Dyson détecté sur ce Wi-Fi" else null,
                )
            }
        }
    }

    fun useDiscovered(device: DiscoveredDevice) {
        _state.update {
            it.copy(
                manualHost = device.host,
                manualSerial = device.serial,
                manualDeviceType = device.deviceType,
            )
        }
    }

    /** Saves a manually entered machine after proving we can actually reach it. */
    fun saveManual(onDone: () -> Unit) {
        val current = _state.value
        val credential = if (current.useWifiPassword) {
            if (current.manualWifiPassword.isBlank()) {
                _state.update { it.copy(error = "Saisissez le mot de passe Wi-Fi de l'appareil") }
                return
            }
            DysonCrypto.credentialFromWifiPassword(current.manualWifiPassword)
        } else {
            current.manualCredential
        }

        if (current.manualSerial.isBlank() || current.manualDeviceType.isBlank() || credential.isBlank()) {
            _state.update { it.copy(error = "Numéro de série, type et identifiant sont requis") }
            return
        }

        val device = DysonDevice(
            serial = current.manualSerial,
            name = "Dyson ${current.manualSerial.takeLast(4)}",
            deviceType = current.manualDeviceType,
            credential = credential,
            host = current.manualHost.takeIf { it.isNotBlank() },
        )

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val saved = runCatching { DysonServices.repository.saveDevice(device) }
            saved.onFailure { error ->
                _state.update { it.copy(busy = false, error = error.message) }
                return@launch
            }

            DysonServices.repository.refreshState()
                .onSuccess {
                    _state.update { it.copy(busy = false, step = OnboardingStep.DONE) }
                    onDone()
                }
                .onFailure { error ->
                    // Keep the machine configured: the details may be right and the
                    // machine merely asleep, and the user can retry from Home.
                    _state.update {
                        it.copy(
                            busy = false,
                            error = "${error.message}. Vérifiez l'adresse et le mot de passe.",
                        )
                    }
                }
        }
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 7_000L
    }
}

/** Address of the first discovered device with a matching serial. */
private suspend fun Flow<DiscoveredDevice>.firstMatching(serial: String): String? =
    firstOrNull { it.serial.equals(serial, ignoreCase = true) }?.host
