package com.radardeal.app

import android.content.Context
import com.radardeal.app.data.local.RadarDatabase
import com.radardeal.app.data.prefs.SettingsStore
import com.radardeal.app.data.repository.ListingRepository
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.monitoring.RadarCoordinator
import com.radardeal.app.monitoring.ScanEngine
import com.radardeal.app.notifications.RadarNotifications
import com.radardeal.app.web.HttpVintedFetcher
import com.radardeal.app.web.VintedFetcher
import com.radardeal.app.web.VintedSession
import com.radardeal.app.web.WebViewVintedFetcher

/**
 * RadarDeal's object graph.
 *
 * A hand-written service locator rather than a DI framework, on purpose: every dependency here
 * is a plain singleton with an obvious lifetime, and each one is created **lazily**. Nothing is
 * constructed during `Application.onCreate`, so a failure in the database, the WebView provider
 * or the cookie store cannot turn into a crash at launch — the app starts, and the failing
 * subsystem reports its own error state where the user can see it.
 */
class AppGraph(context: Context) {

    /** Application context — safe to retain, and the only Context the graph ever holds. */
    val appContext: Context = context.applicationContext

    val database: RadarDatabase by lazy { RadarDatabase.build(appContext) }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    val session: VintedSession by lazy { VintedSession(appContext) }

    val watchRepository: WatchRepository by lazy {
        WatchRepository(database.watchDao(), database.listingDao())
    }

    val listingRepository: ListingRepository by lazy {
        ListingRepository(database.listingDao(), database.pricePointDao())
    }

    val notifications: RadarNotifications by lazy { RadarNotifications(appContext) }

    val fetcher: VintedFetcher by lazy {
        VintedFetcher(
            http = HttpVintedFetcher(session),
            webView = WebViewVintedFetcher(appContext),
        )
    }

    val scanEngine: ScanEngine by lazy {
        ScanEngine(fetcher, watchRepository, listingRepository, notifications)
    }

    val coordinator: RadarCoordinator by lazy {
        RadarCoordinator(watchRepository, scanEngine, settingsStore)
    }
}
