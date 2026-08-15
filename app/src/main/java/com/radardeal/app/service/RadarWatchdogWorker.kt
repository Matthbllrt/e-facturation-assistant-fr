package com.radardeal.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.radardeal.app.RadarDealApp
import com.radardeal.app.core.RdLog
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Safety net for the foreground service.
 *
 * WorkManager cannot poll every 30 seconds — 15 minutes is the platform floor — so this is not
 * the monitoring mechanism. It exists because Android *will* eventually stop a long-running
 * foreground service (the Android 15 `dataSync` budget, OEM battery managers, a low-memory
 * kill), and without this the radar would just stop and never say so.
 *
 * Each run does two things: perform one scan pass, so at worst the user still gets alerts every
 * quarter of an hour, and restart the foreground service when it should be running but is not.
 */
class RadarWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val graph = RadarDealApp.graph(applicationContext)
            val settings = graph.settingsStore.settings.first()

            if (!settings.monitoringRequested) {
                RdLog.d("Watchdog", "monitoring not requested, nothing to do")
                return Result.success()
            }

            val activeWatches = graph.watchRepository.getActive()
            if (activeWatches.isEmpty()) {
                RdLog.d("Watchdog", "no active watch")
                return Result.success()
            }

            // One catch-up pass regardless of the service state — cheap, and it guarantees a
            // floor on how stale the data can get.
            val tick = graph.coordinator.tick()
            RdLog.i(
                "Watchdog",
                "catch-up tick: ${tick.scanned} scanned, ${tick.newListings} new"
            )

            if (!RadarService.isRunning.value) {
                RdLog.i("Watchdog", "service is down, restarting it")
                RadarService.start(applicationContext)
            }

            Result.success()
        } catch (t: Throwable) {
            RdLog.e("Watchdog", "run failed", t)
            // Never Result.failure(): that would cancel the periodic chain for good.
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "radardeal-watchdog"

        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<RadarWatchdogWorker>(
                    15, TimeUnit.MINUTES,
                ).setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { RdLog.w("Watchdog", "could not schedule", it) }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
                .onFailure { RdLog.w("Watchdog", "could not cancel", it) }
        }
    }
}
