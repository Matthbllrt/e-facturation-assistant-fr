package com.glasscontrol.dyson.widget

import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.domain.model.ConnectionStatus
import com.glasscontrol.dyson.domain.model.DysonCapabilities
import com.glasscontrol.dyson.domain.model.DysonDevice
import com.glasscontrol.dyson.domain.model.DysonState

/**
 * Every situation the widget can be in.
 *
 * Modelled explicitly so the widget can never end up rendering a contradiction —
 * "Online" next to stale values, or a spinner that outlives its command.
 */
enum class WidgetStatus {
    /** Configured, but nothing has been read from the machine yet. */
    CONNECTING,

    /** Reachable and idle. */
    ONLINE_OFF,

    /** Reachable and running. */
    ONLINE_ON,

    /** Last attempt could not reach the machine; values shown are the last known. */
    OFFLINE,

    /** The machine itself reported a fault. */
    ERROR,

    /** A command or refresh is in flight over an otherwise healthy connection. */
    REFRESHING,

    /** Onboarding has not been completed. */
    NOT_CONFIGURED,
}

/**
 * Everything one widget instance needs to paint itself.
 *
 * Flattened deliberately: [DysonState] is the protocol-facing model, this is the
 * view-facing one, and keeping them apart means a protocol change cannot silently
 * reshape the home screen.
 */
data class WidgetUiState(
    val status: WidgetStatus,
    val deviceName: String,
    val model: String,
    val power: Boolean,
    val fanSpeed: Int?,
    val autoMode: Boolean,
    val oscillation: Boolean,
    val nightMode: Boolean,
    val heating: Boolean,
    val capabilities: DysonCapabilities,
    val config: WidgetConfig,
) {
    val isOnline: Boolean
        get() = status == WidgetStatus.ONLINE_ON ||
            status == WidgetStatus.ONLINE_OFF ||
            status == WidgetStatus.REFRESHING

    val configured: Boolean get() = status != WidgetStatus.NOT_CONFIGURED

    /** Short badge shown next to the model, e.g. "Online". */
    val statusLabel: String
        get() = when (status) {
            WidgetStatus.CONNECTING -> "Connexion"
            WidgetStatus.ONLINE_OFF, WidgetStatus.ONLINE_ON -> "Online"
            WidgetStatus.REFRESHING -> "Maj…"
            WidgetStatus.OFFLINE -> "Offline"
            WidgetStatus.ERROR -> "Erreur"
            WidgetStatus.NOT_CONFIGURED -> "Non configuré"
        }

    /** The speed to display: two digits, or AUTO when the machine decides. */
    val speedLabel: String
        get() = when {
            !power -> "--"
            autoMode && fanSpeed == null -> "AU"
            fanSpeed != null -> fanSpeed.toString().padStart(2, '0')
            else -> "--"
        }

    companion object {

        fun from(
            device: DysonDevice?,
            state: DysonState,
            capabilities: DysonCapabilities,
            config: WidgetConfig,
        ): WidgetUiState {
            if (device == null) {
                return WidgetUiState(
                    status = WidgetStatus.NOT_CONFIGURED,
                    deviceName = "Dyson",
                    model = "",
                    power = false,
                    fanSpeed = null,
                    autoMode = false,
                    oscillation = false,
                    nightMode = false,
                    heating = false,
                    capabilities = capabilities,
                    config = config,
                )
            }

            return WidgetUiState(
                status = statusOf(state),
                deviceName = config.customName?.takeIf { it.isNotBlank() } ?: device.name,
                model = device.shortModel,
                power = state.power,
                fanSpeed = state.fanSpeed,
                autoMode = state.autoMode,
                oscillation = state.oscillation,
                nightMode = state.nightMode,
                heating = state.heating,
                capabilities = capabilities,
                config = config,
            )
        }

        /**
         * Collapses the connection and the machine's own reading into one status.
         *
         * Precedence matters: being unreachable outranks a stale fault code, and a
         * command in flight outranks the steady on/off state so the widget shows
         * that something is happening.
         */
        private fun statusOf(state: DysonState): WidgetStatus = when {
            state.connection == ConnectionStatus.OFFLINE -> WidgetStatus.OFFLINE

            state.connection == ConnectionStatus.CONNECTING ->
                // Never read anything yet means connecting; otherwise this is a
                // command or refresh over a connection that already worked.
                if (state.lastUpdatedEpochMs == 0L) WidgetStatus.CONNECTING
                else WidgetStatus.REFRESHING

            state.connection == ConnectionStatus.UNKNOWN -> WidgetStatus.CONNECTING

            state.errorCode != null -> WidgetStatus.ERROR

            state.power -> WidgetStatus.ONLINE_ON

            else -> WidgetStatus.ONLINE_OFF
        }
    }
}
