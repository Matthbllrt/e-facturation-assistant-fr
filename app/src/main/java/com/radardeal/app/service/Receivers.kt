package com.radardeal.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.radardeal.app.RadarDealApp
import com.radardeal.app.core.RdLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the "Arrêter" action on the persistent notification. */
class RadarServiceActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        RdLog.i("Receiver", "stop action from notification")

        val appContext = context.applicationContext
        MonitoringController.stop(appContext)

        // Remember the choice, so a reboot does not silently restart the radar.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RadarDealApp.graph(appContext).settingsStore.setMonitoringRequested(false)
            } catch (t: Throwable) {
                RdLog.w("Receiver", "could not persist stop", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.radardeal.app.action.NOTIFICATION_STOP"
    }
}

/**
 * Restores monitoring after a reboot or an app update — but only if the user had it running.
 *
 * A foreground service cannot always be started straight from `BOOT_COMPLETED` on recent
 * Android versions, so the actual restart is delegated to a one-shot worker, which the system
 * runs as soon as it is willing to.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        RdLog.i("Receiver", "boot/update received")

        val appContext = context.applicationContext
        runCatching {
            WorkManager.getInstance(appContext)
                .enqueue(OneTimeWorkRequestBuilder<RadarWatchdogWorker>().build())
            RadarWatchdogWorker.schedule(appContext)
        }.onFailure { RdLog.w("Receiver", "could not enqueue restart", it) }
    }
}
