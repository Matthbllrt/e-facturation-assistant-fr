package com.radardeal.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.radardeal.app.core.RdLog

/**
 * Application entry point.
 *
 * The single most important property of this class is that **`onCreate` cannot fail**. Every
 * previous attempt at this app died at launch, so nothing heavy happens here: no database is
 * opened, no WebView is created, no network client is built. The object graph is allocated but
 * all of its members are lazy, and WorkManager uses on-demand initialisation (its automatic
 * initialiser is removed in the manifest) so that it is only touched the first time a worker is
 * actually scheduled.
 */
class RadarDealApp : Application(), Configuration.Provider {

    /** Allocated eagerly, but every dependency inside it is created on first use. */
    val appGraph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        RdLog.i("App", "RadarDeal ${BuildConfig.VERSION_NAME} starting")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.VERBOSE_LOGGING) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    companion object {
        /**
         * Resolves the graph from any [Context]. Falls back to building a standalone graph if
         * the application object is somehow not a [RadarDealApp] — which keeps unit tests and
         * unusual process states working instead of throwing a ClassCastException.
         */
        fun graph(context: Context): AppGraph {
            val app = context.applicationContext
            return (app as? RadarDealApp)?.appGraph ?: fallbackGraph(app)
        }

        @Volatile
        private var fallback: AppGraph? = null

        private fun fallbackGraph(context: Context): AppGraph =
            fallback ?: synchronized(this) {
                fallback ?: AppGraph(context).also {
                    fallback = it
                    RdLog.w("App", "using fallback graph")
                }
            }
    }
}
