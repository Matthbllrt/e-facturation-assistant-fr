package com.radardeal.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.radardeal.app.RadarDealApp
import com.radardeal.app.core.RdLog
import com.radardeal.app.notifications.RadarNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The foreground service that keeps the radar running while the app is in the background.
 *
 * **Why a foreground service and not WorkManager.** Android's background schedulers have a
 * 15-minute floor — nothing in the platform will run a job every 30 seconds from the
 * background. A user-visible foreground service is the only supported way to poll at the
 * frequencies RadarDeal offers, which is exactly why the persistent notification exists and
 * carries an "Arrêter" action.
 *
 * **What Android still imposes** (see README, "Limites Android"): on Android 15+ a `dataSync`
 * foreground service is capped at roughly 6 hours of runtime per 24 hours, and aggressive
 * OEM battery managers may stop it sooner. [RadarWatchdogWorker] therefore also runs on
 * WorkManager's own schedule and restarts the service — monitoring resumes on its own, at a
 * lower frequency, rather than dying silently.
 */
class RadarService : LifecycleService() {

    private var loopJob: Job? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        RdLog.i("Service", "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            RdLog.i("Service", "stop requested")
            stopMonitoring()
            return START_NOT_STICKY
        }

        val promoted = promoteToForeground()
        if (!promoted) {
            // Android refused to let us run in the foreground (usually a background start on
            // Android 12+). Fail cleanly instead of crashing; the watchdog will retry later.
            stopSelf()
            return START_NOT_STICKY
        }

        if (loopJob?.isActive != true) {
            loopJob = lifecycleScope.launch(Dispatchers.Default) { runLoop() }
        }

        _isRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        RdLog.i("Service", "destroyed")
        loopJob?.cancel()
        loopJob = null
        _isRunning.value = false
        runCatching {
            val graph = graph()
            graph.coordinator.liveEventSink = null
            graph.fetcher.release()
            // Detaching touches the WebView, which must happen on the main thread.
            lifecycleScope.launch(Dispatchers.Main) { graph.liveDomChannel.detach() }
        }
        super.onDestroy()
    }

    // --- Loop ---------------------------------------------------------------------------------

    private suspend fun runLoop() {
        val graph = graph()
        RdLog.i("Service", "watch loop started")

        // The very first notification was built before the database had been read; correct its
        // subtitle as soon as we know the real number of active watches.
        refreshNotification()

        // The live DOM channel reports from the WebView's thread; give it a scope that can run
        // suspending work without blocking the observer.
        graph.coordinator.liveEventSink = { watchId, events, isChallenge ->
            lifecycleScope.launch(Dispatchers.Default) {
                runCatching {
                    if (isChallenge) {
                        graph.coordinator.pauseForVerification(watchId)
                        refreshNotification()
                    } else {
                        val detected = graph.coordinator.processLiveDetections(watchId, events)
                        if (detected > 0) refreshNotification()
                    }
                }.onFailure { RdLog.w("Service", "live event handling failed", it) }
            }
        }
        runCatching { graph.coordinator.syncLiveChannel() }

        while (lifecycleScope.isActive) {
            val result = runCatching { graph.coordinator.tick() }
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    RdLog.e("Service", "tick failed", throwable)
                }
                .getOrNull()

            // Keep the live channel parked on whichever watch currently holds Ultra.
            runCatching { graph.coordinator.syncLiveChannel() }

            if (result != null && result.scanned > 0) {
                RdLog.d(
                    "Service",
                    "tick: ${result.scanned} scanned, ${result.newListings} new, " +
                        "${result.priceDrops} price drops"
                )
                refreshNotification()
            }

            // Stop by ourselves once the user has paused or removed every watch, rather than
            // holding a foreground notification for nothing.
            val activeCount = runCatching { graph.watchRepository.getActive().size }.getOrDefault(0)
            if (activeCount == 0) {
                RdLog.i("Service", "no active watch left, stopping")
                withContext(Dispatchers.Main) { stopMonitoring() }
                return
            }

            // Sleep exactly until the next watch is due instead of ticking on a fixed cadence.
            // The old loop had a 5 s floor and slept for a third of the shortest interval, which
            // alone made anything faster than 15 s pointless. The floor here exists only so a
            // pathological schedule cannot spin the CPU.
            val sleep = runCatching { graph.coordinator.millisUntilNextDue() }
                .getOrDefault(DEFAULT_SLEEP_MS)
                .coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS)
            delay(sleep)
        }
    }

    private suspend fun refreshNotification() {
        val graph = graph()
        val count = runCatching { graph.watchRepository.getActive().size }.getOrDefault(0)
        val ultra = runCatching { graph.watchRepository.getUltraWatch() }.getOrNull()
            ?.takeIf { it.isActive }
        lastKnownActiveCount = count
        runCatching { graph.notifications.refreshServiceNotification(count, ultra?.name) }
            .onFailure { RdLog.w("Service", "could not refresh notification") }
    }

    // --- Foreground plumbing --------------------------------------------------------------------

    private fun promoteToForeground(): Boolean = try {
        val graph = graph()
        val notification = graph.notifications.buildServiceNotification(activeWatchesHint, null)
        ServiceCompat.startForeground(
            this,
            RadarNotifications.SERVICE_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        true
    } catch (t: Throwable) {
        RdLog.e("Service", "startForeground refused", t)
        false
    }

    private fun stopMonitoring() {
        loopJob?.cancel()
        loopJob = null
        _isRunning.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun graph() = RadarDealApp.graph(this)

    /**
     * Best-effort count used only for the very first notification, before the loop has read
     * the database. Refreshed a moment later by [refreshNotification].
     */
    private val activeWatchesHint: Int get() = lastKnownActiveCount

    companion object {
        const val ACTION_START = "com.radardeal.app.action.START"
        const val ACTION_STOP = "com.radardeal.app.action.STOP"

        /** Low enough for Ultra's ~3 s target, high enough that the loop cannot busy-wait. */
        private const val MIN_SLEEP_MS = 250L
        private const val MAX_SLEEP_MS = 30_000L
        private const val DEFAULT_SLEEP_MS = 5_000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        @Volatile
        internal var lastKnownActiveCount: Int = 0

        fun start(context: Context) {
            val intent = Intent(context, RadarService::class.java).setAction(ACTION_START)
            try {
                // minSdk is 26, so a foreground start is always the right call here.
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                // Android 12+ throws ForegroundServiceStartNotAllowedException when the app is
                // in the background. Nothing is broken — the watchdog will pick it up.
                RdLog.w("Service", "start refused: ${t.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RadarService::class.java).setAction(ACTION_STOP)
                )
            }.onFailure {
                runCatching { context.stopService(Intent(context, RadarService::class.java)) }
            }
        }
    }
}
