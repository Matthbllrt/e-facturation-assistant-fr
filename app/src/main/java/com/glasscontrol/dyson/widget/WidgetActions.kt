package com.glasscontrol.dyson.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.core.logW
import com.glasscontrol.dyson.data.local.CommandEncoder
import com.glasscontrol.dyson.domain.model.DysonCommand
import com.glasscontrol.dyson.domain.model.DysonState
import com.glasscontrol.dyson.work.CommandWorker

/** Intent names exchanged between the widget UI and the worker. */
object WidgetCommands {
    const val TOGGLE_POWER = "toggle_power"
    const val TOGGLE_AUTO = "toggle_auto"
    const val TOGGLE_OSCILLATION = "toggle_oscillation"
    const val TOGGLE_NIGHT = "toggle_night"
    const val TOGGLE_HEAT = "toggle_heat"
    const val SPEED_UP = "speed_up"
    const val SPEED_DOWN = "speed_down"
    const val REFRESH = "refresh"

    val KEY = ActionParameters.Key<String>("dyson_command")

    /**
     * Resolves a tap into an absolute command against [state].
     *
     * Deliberately absolute rather than relative: the tap handler writes an
     * optimistic state immediately, so a worker that later re-derived "toggle"
     * from the cache would read its own optimistic value and flip it straight
     * back. Deciding once, here, removes that whole class of bug.
     *
     * @return null for [REFRESH], which is not a state change.
     */
    fun toCommand(name: String, state: DysonState): DysonCommand? = when (name) {
        TOGGLE_POWER -> DysonCommand.SetPower(!state.power)
        TOGGLE_AUTO -> DysonCommand.SetAutoMode(!state.autoMode)
        TOGGLE_OSCILLATION -> DysonCommand.SetOscillation(!state.oscillation)
        TOGGLE_NIGHT -> DysonCommand.SetNightMode(!state.nightMode)
        TOGGLE_HEAT -> DysonCommand.SetHeating(!state.heating)
        SPEED_UP -> DysonCommand.SetFanSpeed(CommandEncoder.steppedSpeed(state.fanSpeed, 1))
        SPEED_DOWN -> DysonCommand.SetFanSpeed(CommandEncoder.steppedSpeed(state.fanSpeed, -1))
        else -> null
    }
}

/**
 * Runs a widget tap.
 *
 * The tap paints its own result straight away and hands the actual send to a
 * background job, so the launcher never waits on a network round trip and the
 * chip never sits inert while work is scheduled.
 */
class DysonCommandAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val name = parameters[WidgetCommands.KEY] ?: return
        DysonServices.ensureInitialised(context)

        val command = runCatching {
            val state = DysonServices.repository.getState()
            WidgetCommands.toCommand(name, state)
        }.getOrElse { error ->
            logW("Could not resolve widget command '$name'", error)
            null
        }

        // Immediate feedback, before anything is queued.
        if (command != null) {
            runCatching { DysonServices.repository.previewCommand(command) }
                .onFailure { logW("Optimistic update failed", it) }
        }

        CommandWorker.enqueue(context, name, command)
    }
}
