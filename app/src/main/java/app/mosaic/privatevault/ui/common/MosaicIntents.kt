package app.mosaic.privatevault.ui.common

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.mosaic.privatevault.MainActivity
import app.mosaic.privatevault.core.log.SafeLog

/** Outbound intents. Everything that leaves Mosaic goes through here. */
object MosaicIntents {

    private const val INSTAGRAM_PACKAGE = "com.instagram.android"

    fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    enum class OpenResult { OPENED_CONVERSATION, OPENED_APP, NOT_INSTALLED }

    /**
     * Opens Instagram as close to the conversation as officially possible.
     *
     * `ig.me/m/<username>` is Meta's own click-to-message link and is the only
     * supported way to land in a specific thread. Without a handle — the usual
     * case in notification mode — Mosaic opens the direct inbox instead of
     * guessing, and says so in the UI.
     */
    fun openInstagramConversation(context: Context, handle: String?): OpenResult {
        if (!isInstagramInstalled(context)) {
            openPlayStoreListing(context)
            return OpenResult.NOT_INSTALLED
        }

        if (!handle.isNullOrBlank()) {
            val cleaned = handle.removePrefix("@").trim()
            val direct = Intent(Intent.ACTION_VIEW, "https://ig.me/m/$cleaned".toUri()).apply {
                setPackage(INSTAGRAM_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (tryStart(context, direct)) return OpenResult.OPENED_CONVERSATION
        }

        val inbox = context.packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (inbox != null && tryStart(context, inbox)) return OpenResult.OPENED_APP
        return OpenResult.NOT_INSTALLED
    }

    fun isInstagramInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE) != null
    }.getOrDefault(false)

    fun openNotificationAccessSettings(context: Context): Boolean {
        val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return tryStart(context, intent)
    }

    private fun openPlayStoreListing(context: Context) {
        val market = Intent(
            Intent.ACTION_VIEW,
            "market://details?id=$INSTAGRAM_PACKAGE".toUri(),
        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        if (tryStart(context, market)) return
        tryStart(
            context,
            Intent(
                Intent.ACTION_VIEW,
                "https://play.google.com/store/apps/details?id=$INSTAGRAM_PACKAGE".toUri(),
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
        )
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        SafeLog.d("intents.no_handler")
        false
    } catch (error: SecurityException) {
        SafeLog.w("intents.denied", error)
        false
    }
}
