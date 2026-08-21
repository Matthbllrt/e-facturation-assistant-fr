package app.mosaic.privatevault.ui.common

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.mosaic.privatevault.R
import app.mosaic.privatevault.domain.model.NotificationPolicy

/**
 * The only place Mosaic is allowed to touch the status bar.
 *
 * Default policy is [NotificationPolicy.NONE] — nothing at all. When the user
 * opts in, the notification is deliberately contentless: no alias, no name, no
 * text, no preview, nothing that a shoulder-surfer or a lock-screen glance can
 * read. It says only that there is something to look at.
 */
class MosaicNotifier(private val context: Context) {

    fun notifyNewActivity(policy: NotificationPolicy) {
        if (policy == NotificationPolicy.NONE) return
        // Inline rather than delegated so lint can see the guard; the user may
        // revoke POST_NOTIFICATIONS at any time after opting in.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_neutral_title))
            // No content text, no big text, no messaging style: there is nothing
            // safe to put there.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(MosaicIntents.openAppPendingIntent(context))
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun clear() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "mosaic.neutral"
        const val NOTIFICATION_ID = 1
    }
}
