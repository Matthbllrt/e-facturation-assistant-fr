package app.mosaic.privatevault.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mosaic.privatevault.MosaicGraph
import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.domain.model.SyncMode
import app.mosaic.privatevault.sync.api.InstagramApiClient
import app.mosaic.privatevault.sync.api.InstagramSync
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Periodic pull for Mode A. Mode B needs nothing here — notifications push.
 */
class ApiSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!MosaicGraph.isInstalled()) return Result.success()

        val settings = MosaicGraph.settings.settings.first()
        if (settings.syncMode != SyncMode.OFFICIAL_API) return Result.success()

        val repository = MosaicGraph.conversations() ?: return Result.retry()
        val sync = InstagramSync(MosaicGraph.api, MosaicGraph.target)

        return when (val result = sync.sync(repository)) {
            is InstagramSync.Result.Synced -> {
                if (result.stored > 0) {
                    MosaicGraph.settings.settings.first().let { current ->
                        CooldownScheduler.schedule(
                            applicationContext,
                            current.cooldown,
                            System.currentTimeMillis(),
                        )
                    }
                }
                Result.success()
            }

            InstagramSync.Result.NoConversation -> Result.success()

            is InstagramSync.Result.Failed -> when (result.failure) {
                // Nothing is wrong with the app; try again on the next window.
                InstagramApiClient.Failure.Offline,
                is InstagramApiClient.Failure.Server,
                is InstagramApiClient.Failure.RateLimited,
                -> Result.retry()

                // Retrying cannot fix a dead token; the UI prompts for a new one.
                InstagramApiClient.Failure.AuthenticationRequired -> {
                    SafeLog.w("sync.token_invalid")
                    Result.success()
                }

                is InstagramApiClient.Failure.Unexpected -> Result.success()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "mosaic.api_sync"

        fun enable(context: Context) {
            val request = PeriodicWorkRequestBuilder<ApiSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
