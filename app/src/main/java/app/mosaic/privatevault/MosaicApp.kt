package app.mosaic.privatevault

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.domain.model.SyncMode
import app.mosaic.privatevault.sync.notification.ReplyDispatcher
import app.mosaic.privatevault.sync.work.ApiSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MosaicApp : Application(), androidx.work.Configuration.Provider {

    /**
     * WorkManager is initialised on demand rather than by its startup provider
     * (removed in the manifest), so nothing touches the vault before
     * [onCreate] has configured logging.
     */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.ERROR)
            .build()


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        SafeLog.configure(debugBuild = BuildConfig.DEBUG)
        MosaicGraph.install(this)

        // The vault starts locked on every cold start, including after a reboot
        // or after the process was killed in the background.
        MosaicGraph.appLock.lock()

        ProcessLifecycleOwner.get().lifecycle.addObserver(LockLifecycleObserver())

        scope.launch {
            val settings = MosaicGraph.settings.settings.first()
            if (settings.syncMode == SyncMode.OFFICIAL_API && MosaicGraph.api.hasToken()) {
                ApiSyncWorker.enable(this@MosaicApp)
            }
        }
    }

    /**
     * Auto-lock and recents hygiene.
     *
     * Locking happens on the way out rather than on the way back in, so the
     * snapshot Android takes for the recents list is already of a locked
     * screen — `FLAG_SECURE` blanks it anyway, but relying on one mechanism for
     * this is not enough.
     */
    private inner class LockLifecycleObserver : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            MosaicGraph.appLock.noteBackgrounded()
            scope.launch {
                val delay = MosaicGraph.settings.settings.first().autoLock
                MosaicGraph.appLock.refreshOnForeground(delay)
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            scope.launch {
                val delay = MosaicGraph.settings.settings.first().autoLock
                MosaicGraph.appLock.refreshOnForeground(delay)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // A reply PendingIntent held across a long background stint is
            // almost certainly stale by the time it is used.
            ReplyDispatcher.forget()
        }
    }
}
