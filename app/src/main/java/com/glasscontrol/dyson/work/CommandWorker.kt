package com.glasscontrol.dyson.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.core.logW
import com.glasscontrol.dyson.domain.model.DysonCommand
import com.glasscontrol.dyson.widget.WidgetCommands

/**
 * Sends one command to the machine on behalf of a widget tap.
 *
 * The tap handler returns immediately and this runs expedited in the background,
 * so the launcher stays responsive whatever the network does. The repository
 * already wrote an optimistic state before this starts, and overwrites it with
 * the machine's real answer when the round trip completes.
 */
class CommandWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DysonServices.ensureInitialised(applicationContext)
        val commandName = inputData.getString(KEY_COMMAND) ?: return Result.success()
        val repository = DysonServices.repository
        val state = repository.getState()

        val outcome = when (commandName) {
            WidgetCommands.TOGGLE_POWER -> repository.setPower(!state.power)
            WidgetCommands.TOGGLE_AUTO -> repository.setAutoMode(!state.autoMode)
            WidgetCommands.TOGGLE_OSCILLATION -> repository.setOscillation(!state.oscillation)
            WidgetCommands.TOGGLE_NIGHT -> repository.setNightMode(!state.nightMode)
            WidgetCommands.TOGGLE_HEAT -> repository.setHeating(!state.heating)
            WidgetCommands.SPEED_UP -> repository.execute(DysonCommand.StepFanSpeed(1))
            WidgetCommands.SPEED_DOWN -> repository.execute(DysonCommand.StepFanSpeed(-1))
            WidgetCommands.REFRESH -> repository.refreshState()
            else -> return Result.success()
        }

        return outcome.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                // The widget already shows "Offline"; one retry covers a transient
                // Wi-Fi handover, beyond that a further attempt would be stale.
                logW("Widget command '$commandName' failed", error)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            },
        )
    }

    companion object {
        private const val KEY_COMMAND = "command"
        private const val MAX_ATTEMPTS = 2
        private const val WORK_NAME = "dyson_command"

        fun enqueue(context: Context, command: String) {
            val request = OneTimeWorkRequestBuilder<CommandWorker>()
                .setInputData(Data.Builder().putString(KEY_COMMAND, command).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            // APPEND_OR_REPLACE keeps rapid taps in order instead of racing for the
            // machine's single local connection slot.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
