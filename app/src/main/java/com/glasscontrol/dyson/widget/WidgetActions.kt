package com.glasscontrol.dyson.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
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
}

/**
 * Runs a widget tap.
 *
 * The tap itself does almost nothing: it hands the intent to a WorkManager job
 * and returns immediately, so the launcher never waits on a network round trip.
 * The optimistic state written by the repository is what makes the widget react
 * at once.
 */
class DysonCommandAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val command = parameters[WidgetCommands.KEY] ?: return
        CommandWorker.enqueue(context, command)
    }
}
