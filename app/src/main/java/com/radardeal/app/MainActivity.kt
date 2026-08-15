package com.radardeal.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radardeal.app.core.RdLog
import com.radardeal.app.ui.navigation.PendingListing
import com.radardeal.app.ui.navigation.RadarDealNavigation
import com.radardeal.app.ui.theme.RadarColors
import com.radardeal.app.ui.theme.RadarDealTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's only Activity.
 *
 * It does as little as possible: install the splash screen, go edge to edge, and hand over to
 * Compose. Nothing that could fail (database, network, WebView) is touched here — the previous
 * incarnation of this project crashed on launch, and the fix is to have a launch path with
 * almost nothing in it.
 */
class MainActivity : ComponentActivity() {

    private val pendingListing = MutableStateFlow<PendingListing?>(null)

    /** Flipped once the stored preferences have been read, which releases the splash screen. */
    private val contentReady = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Hold the splash for the instant it takes to learn whether onboarding is still due,
        // so the user never sees the main UI flash before the onboarding replaces it.
        splash.setKeepOnScreenCondition { !contentReady.value }

        readDeepLink(intent)

        setContent {
            RadarDealTheme {
                val graph = remember { RadarDealApp.graph(this) }
                val settings by graph.settingsStore.settings
                    .collectAsStateWithLifecycle(initialValue = null)

                LaunchedEffect(settings) {
                    if (settings != null) contentReady.value = true
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RadarColors.Background)
                ) {
                    val loaded = settings
                    if (loaded != null) {
                        val pending by pendingListing.collectAsState()
                        RadarDealNavigation(
                            startWithOnboarding = !loaded.onboardingDone,
                            pendingListing = pending,
                            onPendingListingHandled = { pendingListing.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeepLink(intent)
    }

    /** Notification taps carry the watch and listing to open. */
    private fun readDeepLink(intent: Intent?) {
        val watchId = intent?.getLongExtra(EXTRA_WATCH_ID, -1L) ?: -1L
        val itemId = intent?.getStringExtra(EXTRA_ITEM_ID)
        if (watchId > 0L && !itemId.isNullOrBlank()) {
            RdLog.d("MainActivity", "deep link to listing")
            pendingListing.value = PendingListing(watchId, itemId)
        }
    }

    companion object {
        const val ACTION_OPEN_LISTING = "com.radardeal.app.action.OPEN_LISTING"
        const val EXTRA_WATCH_ID = "extra_watch_id"
        const val EXTRA_ITEM_ID = "extra_item_id"
    }
}
