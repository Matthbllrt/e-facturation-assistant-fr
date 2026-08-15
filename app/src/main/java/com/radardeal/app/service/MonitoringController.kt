package com.radardeal.app.service

import android.content.Context
import com.radardeal.app.core.RdLog

/**
 * The one place that turns monitoring on and off.
 *
 * Starting monitoring means three things at once: run the foreground service now, remember
 * that the user wants it running (so a reboot or a system-initiated kill can restore it), and
 * arm the WorkManager watchdog that brings the service back if Android stops it.
 */
object MonitoringController {

    fun start(context: Context) {
        RdLog.i("Monitoring", "start requested")
        RadarService.start(context)
        RadarWatchdogWorker.schedule(context)
    }

    fun stop(context: Context) {
        RdLog.i("Monitoring", "stop requested")
        RadarService.stop(context)
        RadarWatchdogWorker.cancel(context)
    }

    val isRunning get() = RadarService.isRunning
}
