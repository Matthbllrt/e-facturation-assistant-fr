package app.mosaic.privatevault.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mosaic.privatevault.MosaicGraph
import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.domain.cooldown.CooldownPolicy
import app.mosaic.privatevault.domain.model.Cooldown
import java.util.concurrent.TimeUnit

/**
 * Fires when the cooldown elapses and flips the "cleanup ready" flag.
 *
 * It performs no Instagram operation whatsoever. Meta exposes no supported way
 * to delete a conversation, so the end of a cooldown is an invitation for the
 * user to do it themselves — never a claim that anything was deleted.
 */
class CooldownWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!MosaicGraph.isInstalled()) return Result.success()
        MosaicGraph.settings.setCleanupReadyAt(System.currentTimeMillis())
        SafeLog.i("cooldown.ready")
        return Result.success()
    }
}

object CooldownScheduler {

    private const val WORK_NAME = "mosaic.cooldown"

    /**
     * (Re)arms the cooldown. Called on every new message, so the delay is always
     * measured from the latest activity rather than the first.
     */
    fun schedule(context: Context, cooldown: Cooldown, lastActivityMillis: Long) {
        val manager = WorkManager.getInstance(context)
        if (cooldown.isOff) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }

        val delay = CooldownPolicy.scheduleDelayMillis(
            nowMillis = System.currentTimeMillis(),
            lastActivityMillis = lastActivityMillis,
            cooldown = cooldown,
        ) ?: return

        val request = OneTimeWorkRequestBuilder<CooldownWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        // REPLACE, so the newest message pushes the deadline out rather than
        // leaving a stale worker to fire early.
        manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
