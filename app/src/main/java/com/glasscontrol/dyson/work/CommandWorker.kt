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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sends one already-resolved command to the machine on behalf of a widget tap.
 *
 * The tap handler decided what to send and has already painted the result, so
 * this only has to deliver it. Running here rather than in the tap keeps the
 * launcher responsive whatever the network does.
 */
class CommandWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DysonServices.ensureInitialised(applicationContext)
        val repository = DysonServices.repository

        val name = inputData.getString(KEY_NAME)
        if (name == WidgetCommands.REFRESH) {
            repository.refreshState()
            return Result.success()
        }

        val encoded = inputData.getString(KEY_COMMAND) ?: return Result.success()
        val command = runCatching { json.decodeFromString<DysonCommand>(encoded) }
            .getOrElse { error ->
                logW("Unreadable widget command", error)
                return Result.success()
            }

        repository.execute(command).onFailure { error ->
            // The widget already shows Offline through the repository's own state
            // write. Retrying here would stall every queued tap behind WorkManager's
            // ten-second-minimum backoff, which reads as "the widget is dead", so
            // one tap means one attempt.
            logW("Widget command failed", error)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_COMMAND = "command"
        private const val WORK_NAME = "dyson_command"

        private val json = Json { ignoreUnknownKeys = true }

        fun enqueue(context: Context, name: String, command: DysonCommand?) {
            val data = Data.Builder()
                .putString(KEY_NAME, name)
                .apply {
                    command?.let { putString(KEY_COMMAND, json.encodeToString(it)) }
                }
                .build()

            val request = OneTimeWorkRequestBuilder<CommandWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            // APPEND_OR_REPLACE keeps rapid taps in order instead of racing for the
            // machine's single local connection slot.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
