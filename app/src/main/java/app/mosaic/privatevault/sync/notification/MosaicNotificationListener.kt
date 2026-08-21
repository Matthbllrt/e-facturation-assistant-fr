package app.mosaic.privatevault.sync.notification

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.mosaic.privatevault.MosaicGraph
import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.data.repo.MessageCapture
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MessageSource
import app.mosaic.privatevault.domain.notify.NotificationMatcher
import app.mosaic.privatevault.sync.work.CooldownScheduler
import app.mosaic.privatevault.ui.common.MosaicNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Mode B capture path.
 *
 * Reads Instagram's own notifications and keeps only those belonging to the one
 * selected conversation. It asks for no Instagram credentials, sends nothing
 * anywhere, and works with no server — which is the whole reason it exists,
 * since Meta's messaging API is closed to consumer accounts.
 *
 * What it fundamentally cannot do, and never pretends to: recover history from
 * before it was enabled, or see a message that arrived with notifications
 * disabled. Android does not expose those.
 */
class MosaicNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedState.value = true
        SafeLog.i("listener.connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connectedState.value = false
        ReplyDispatcher.forget()
        SafeLog.i("listener.disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NotificationMatcher.INSTAGRAM_PACKAGES) return
        if (!MosaicGraph.isInstalled()) return

        // Copy everything off the StatusBarNotification synchronously: the
        // system may recycle it as soon as this method returns.
        val candidate = NotificationExtractor.toCandidate(sbn)
        val extracted = NotificationExtractor.extractMessages(sbn)
        val replyAction = NotificationExtractor.findReplyAction(sbn.notification)

        scope.launch { handle(candidate, extracted, replyAction) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Instagram withdrawing the notification invalidates its reply
        // PendingIntent. The stored messages are untouched: a message that has
        // been captured is never removed because Instagram changed its mind.
        if (sbn.packageName in NotificationMatcher.INSTAGRAM_PACKAGES) {
            ReplyDispatcher.forget()
        }
    }

    private suspend fun handle(
        candidate: app.mosaic.privatevault.domain.notify.NotificationCandidate,
        extracted: List<NotificationExtractor.ExtractedMessage>,
        replyAction: android.app.Notification.Action?,
    ) {
        // Discovery: during setup Mosaic needs to show which conversations exist
        // without storing anything from them.
        if (DiscoveryFeed.isActive) {
            DiscoveryFeed.offer(candidate)
        }

        val target = MosaicGraph.target.current()
        when (val result = MosaicGraph.notificationMatcher.match(candidate, target)) {
            is NotificationMatcher.Result.Ignored -> {
                SafeLog.d("listener.ignored reason=${result.reason}")
                return
            }

            NotificationMatcher.Result.MatchedByNameOnly -> {
                // Accepted, but the user is told once that name-only matching is
                // in use, because a namesake would be captured too.
                AmbiguityFlag.raise()
            }

            NotificationMatcher.Result.Matched -> AmbiguityFlag.clear()
        }

        replyAction?.let(ReplyDispatcher::remember)

        val repository = MosaicGraph.conversations()
        if (repository == null) {
            SafeLog.e("listener.vault_unavailable")
            return
        }

        var stored = 0
        for (message in extracted) {
            if (message.text.isNullOrBlank() && message.mediaKind.name == "TEXT") continue
            val outcome = repository.record(
                MessageCapture(
                    timestamp = message.timestamp,
                    direction = message.direction,
                    text = message.text,
                    mediaKind = message.mediaKind,
                    status = DeliveryStatus.RECEIVED,
                    source = MessageSource.ANDROID_NOTIFICATION,
                ),
            )
            if (outcome is app.mosaic.privatevault.data.repo.ConversationRepository.Outcome.Stored) {
                stored++
            }
        }

        if (stored == 0) return

        val settings = MosaicGraph.settings.settings.first()
        MosaicNotifier(applicationContext).notifyNewActivity(settings.notificationPolicy)
        CooldownScheduler.schedule(applicationContext, settings.cooldown, candidate.postTime)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val connectedState = MutableStateFlow(false)

        /** True while Android has the listener bound. */
        val connected: StateFlow<Boolean> = connectedState.asStateFlow()

        /**
         * Whether the user has granted notification access. Checked every time
         * rather than cached: the permission can be revoked at any moment from
         * system settings, and Mosaic must say so instead of appearing to sync.
         */
        fun isPermissionGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val component = ComponentName(context, MosaicNotificationListener::class.java)
            return enabled.split(':').any {
                val parsed = ComponentName.unflattenFromString(it)
                parsed == component || parsed?.packageName == context.packageName
            }
        }
    }
}
