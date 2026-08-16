package com.radardeal.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.radardeal.app.MainActivity
import com.radardeal.app.R
import com.radardeal.app.core.Formatters
import com.radardeal.app.core.RdLog
import com.radardeal.app.data.prefs.AppSettings
import com.radardeal.app.domain.model.DealRating
import com.radardeal.app.domain.model.Listing
import com.radardeal.app.domain.model.Watch
import com.radardeal.app.monitoring.DealEngine
import com.radardeal.app.monitoring.PriceDrop
import com.radardeal.app.service.RadarServiceActionReceiver
import kotlin.math.absoluteValue

/**
 * All Android notifications RadarDeal posts.
 *
 * Sound and vibration are per-channel settings on Android 8+, and a channel's importance
 * cannot be changed once created. So instead of fighting the platform, the alert channels are
 * *keyed by the user's choice*: switching sound off simply routes new alerts to the silent
 * variant of the channel. That keeps the Réglages toggles truthful on every supported version.
 */
class RadarNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    // --- Public API --------------------------------------------------------------------------

    fun ensureChannels(settings: AppSettings) {
        runCatching {
            createServiceChannel()
            createAlertChannel(newItemsChannelId(settings), R.string.channel_new_items_name,
                R.string.channel_new_items_description, settings, NotificationManager.IMPORTANCE_HIGH)
            createAlertChannel(priceDropChannelId(settings), R.string.channel_price_drops_name,
                R.string.channel_price_drops_description, settings, NotificationManager.IMPORTANCE_DEFAULT)
        }.onFailure { RdLog.w("Notif", "channel creation failed", it) }
    }

    /** The persistent notification shown while the foreground service is running. */
    fun buildServiceNotification(
        activeWatches: Int,
        ultraWatchName: String? = null,
    ): android.app.Notification = serviceNotificationBuilder(activeWatches, ultraWatchName).build()

    /**
     * Updates the ongoing notification in place, e.g. after the number of active watches
     * changed. Silently does nothing when the user has revoked the notification permission —
     * the service itself keeps running, it just stops describing itself.
     */
    fun refreshServiceNotification(activeWatches: Int, ultraWatchName: String? = null) {
        post(SERVICE_NOTIFICATION_ID, serviceNotificationBuilder(activeWatches, ultraWatchName))
    }

    private fun serviceNotificationBuilder(
        activeWatches: Int,
        ultraWatchName: String?,
    ): NotificationCompat.Builder {
        createServiceChannel()

        val stopIntent = PendingIntent.getBroadcast(
            context,
            REQ_STOP,
            Intent(context, RadarServiceActionReceiver::class.java)
                .setAction(RadarServiceActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val subtitle = when {
            ultraWatchName != null -> "$ultraWatchName surveillé"
            activeWatches == 0 -> "Aucune recherche active"
            activeWatches == 1 -> "1 recherche surveillée"
            else -> "$activeWatches recherches surveillées"
        }

        val title = if (ultraWatchName != null) {
            context.getString(R.string.service_title_ultra)
        } else {
            context.getString(R.string.service_title)
        }

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openAppIntent())
            .addAction(0, context.getString(R.string.service_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    fun notifyNewListings(
        watch: Watch,
        listings: List<Listing>,
        watchPrices: List<Double>,
        settings: AppSettings,
    ) {
        if (listings.isEmpty() || !canPost()) return
        ensureChannels(settings)

        val channel = newItemsChannelId(settings)
        val shown = listings.sortedByDescending { it.firstSeenAt }.take(MAX_INDIVIDUAL)

        for (listing in shown) {
            val assessment = DealEngine.assess(listing.price, watchPrices)
            val body = buildString {
                append(listing.displayTitle)
                append('\n')
                append(Formatters.price(listing.price, listing.currency))
                listing.size?.takeIf { it.isNotBlank() }?.let { append("\nTaille ").append(it) }
                if (assessment.rating != DealRating.NONE && assessment.discount != null) {
                    append('\n')
                    append(assessment.rating.label)
                    append(" · ")
                    append((assessment.discount * 100).toInt())
                    append(" % sous la médiane")
                }
            }

            post(
                id = notificationId(PREFIX_NEW, watch.id, listing.itemId),
                builder = NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notification_new_item_title))
                    .setContentText("${listing.displayTitle} · ${Formatters.price(listing.price, listing.currency)}")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSubText(watch.name)
                    .setContentIntent(openListingIntent(watch.id, listing.itemId))
                    .setAutoCancel(true)
                    .setGroup(groupKey(watch.id))
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .applyAlertPreferences(settings),
            )
        }

        if (listings.size > 1) {
            postGroupSummary(watch, listings.size, channel, settings)
        }
    }

    fun notifyPriceDrops(watch: Watch, drops: List<PriceDrop>, settings: AppSettings) {
        if (drops.isEmpty() || !canPost()) return
        ensureChannels(settings)

        val channel = priceDropChannelId(settings)
        for (drop in drops.take(MAX_INDIVIDUAL)) {
            val listing = drop.listing
            val currency = listing.currency
            val body = buildString {
                append(listing.displayTitle)
                append('\n')
                append(Formatters.price(drop.oldPrice, currency))
                append(" → ")
                append(Formatters.price(drop.newPrice, currency))
                append("  (-")
                append((drop.ratio * 100).toInt())
                append(" %)")
            }

            post(
                id = notificationId(PREFIX_DROP, watch.id, listing.itemId),
                builder = NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notification_price_drop_title))
                    .setContentText(
                        "${listing.displayTitle} · " +
                            "${Formatters.price(drop.oldPrice, currency)} → " +
                            Formatters.price(drop.newPrice, currency)
                    )
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSubText(watch.name)
                    .setContentIntent(openListingIntent(watch.id, listing.itemId))
                    .setAutoCancel(true)
                    .setGroup(groupKey(watch.id))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .applyAlertPreferences(settings),
            )
        }
    }

    fun cancelAll() = runCatching { manager.cancelAll() }.let { }

    // --- Internals ---------------------------------------------------------------------------

    private fun postGroupSummary(watch: Watch, count: Int, channel: String, settings: AppSettings) {
        post(
            id = notificationId(PREFIX_SUMMARY, watch.id, watch.id.toString()),
            builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$count nouvelles annonces")
                .setContentText(watch.name)
                .setGroup(groupKey(watch.id))
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .applyAlertPreferences(settings),
        )
    }

    /**
     * The single place a notification is handed to Android.
     *
     * [hasPostPermission] is checked first, and the call is still wrapped: POST_NOTIFICATIONS
     * can be revoked between the check and the call on Android 13+, and a revoked permission
     * must never take the monitoring service down with it. The suppression documents exactly
     * that — the permission *is* checked, lint simply cannot follow the call.
     */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, builder: NotificationCompat.Builder) {
        if (!hasPostPermission()) return
        runCatching { manager.notify(id, builder.build()) }
            .onFailure { RdLog.w("Notif", "notify($id) refused: ${it.javaClass.simpleName}") }
    }

    /** True when Android will actually accept a notification from this app right now. */
    private fun hasPostPermission(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        manager.areNotificationsEnabled()
    }.getOrDefault(false)

    private fun canPost(): Boolean = hasPostPermission()

    private fun NotificationCompat.Builder.applyAlertPreferences(
        settings: AppSettings,
    ): NotificationCompat.Builder = apply {
        // Channels own this on Android 8+, but setting it keeps the intent explicit and
        // covers the pre-channel code paths inside NotificationCompat.
        var defaults = 0
        if (settings.soundEnabled) defaults = defaults or NotificationCompat.DEFAULT_SOUND
        if (settings.vibrationEnabled) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
        setDefaults(defaults)
        if (!settings.soundEnabled && !settings.vibrationEnabled) setSilent(true)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, REQ_OPEN, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Tapping an alert opens RadarDeal directly on that listing. */
    private fun openListingIntent(watchId: Long, itemId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("${MainActivity.ACTION_OPEN_LISTING}:$watchId:$itemId")
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_WATCH_ID, watchId)
            .putExtra(MainActivity.EXTRA_ITEM_ID, itemId)
        return PendingIntent.getActivity(
            context,
            notificationId(PREFIX_NEW, watchId, itemId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createServiceChannel() {
        val channel = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.service_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createAlertChannel(
        id: String,
        nameRes: Int,
        descriptionRes: Int,
        settings: AppSettings,
        importance: Int,
    ) {
        val channel = NotificationChannel(id, context.getString(nameRes), importance).apply {
            description = context.getString(descriptionRes)
            enableVibration(settings.vibrationEnabled)
            if (settings.soundEnabled) {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            } else {
                setSound(null, null)
            }
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Channel id encodes the alert preferences, because an existing channel's sound and
     * vibration can no longer be changed by the app.
     */
    private fun newItemsChannelId(settings: AppSettings) =
        "new_items_${variant(settings)}"

    private fun priceDropChannelId(settings: AppSettings) =
        "price_drops_${variant(settings)}"

    private fun variant(settings: AppSettings) =
        "s${settings.soundEnabled.toInt()}v${settings.vibrationEnabled.toInt()}"

    private fun Boolean.toInt() = if (this) 1 else 0

    private fun groupKey(watchId: Long) = "radardeal.watch.$watchId"

    /** Stable, collision-resistant id so a re-notified listing replaces its own notification. */
    private fun notificationId(prefix: Int, watchId: Long, itemId: String): Int {
        val hash = (watchId.hashCode() * 31 + itemId.hashCode()).absoluteValue
        return prefix + (hash % 100_000)
    }

    companion object {
        const val CHANNEL_SERVICE = "radar_service"
        const val SERVICE_NOTIFICATION_ID = 1001

        private const val MAX_INDIVIDUAL = 5
        private const val PREFIX_NEW = 200_000
        private const val PREFIX_DROP = 400_000
        private const val PREFIX_SUMMARY = 600_000
        private const val REQ_OPEN = 10
        private const val REQ_STOP = 11
    }
}
