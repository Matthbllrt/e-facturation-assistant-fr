package com.glasscontrol.dyson.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.core.LogArea
import com.glasscontrol.dyson.core.logW
import com.glasscontrol.dyson.widget.DysonWidgetUpdater
import java.util.concurrent.TimeUnit

/**
 * Periodically refreshes the cached state so a widget left alone stays roughly
 * current.
 *
 * Deliberately infrequent: the machine is only polled when a widget actually
 * exists, and never often enough to matter for battery. Live, to-the-second
 * updates are the app's job, not the widget's.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DysonServices.ensureInitialised(applicationContext)
        if (!DysonWidgetUpdater.hasWidgets(applicationContext)) return Result.success()

        DysonServices.repository.refreshState()
        // The repository repaints widgets on success; on failure the badge flips
        // to offline through the same path, so nothing more is needed here.
        return Result.success()
    }
}

object RefreshScheduler {

    private const val WORK_NAME = "dyson_periodic_refresh"
    private const val INTERVAL_MINUTES = 30L

    /**
     * Registers the periodic refresh.
     *
     * Scheduling is best-effort: the widget still works through direct taps and
     * the app's own reads, so a WorkManager that is not ready must never stop
     * the app from starting.
     */
    fun ensureScheduled(context: Context) = runCatching {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    // Only worth trying when there is a network at all.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }.onFailure { logW(LogArea.WIDGET_UPDATE, "Periodic refresh could not be scheduled", it) }.let { }
}
