package app.mosaic.privatevault

import android.app.ActivityManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.mosaic.privatevault.ui.MosaicRoot
import app.mosaic.privatevault.ui.theme.MosaicTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The single activity.
 *
 * A [FragmentActivity] because BiometricPrompt needs one; everything above it
 * is Compose.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyRecentsDisguise()

        lifecycleScope.launch {
            val settings = MosaicGraph.settings.settings.first()
            applyScreenshotProtection(settings.screenshotProtection)
        }
        // Protection is on until the preference is read, never the other way
        // round: a single unprotected frame is a leaked screenshot.
        applyScreenshotProtection(true)

        setContent {
            MosaicTheme {
                val locked by MosaicGraph.appLock.isLocked.collectAsStateWithLifecycle()
                MosaicRoot(
                    activity = this,
                    locked = locked,
                    onScreenshotProtectionChanged = ::applyScreenshotProtection,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val delay = MosaicGraph.settings.settings.first().autoLock
            MosaicGraph.appLock.refreshOnForeground(delay)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        MosaicGraph.appLock.noteInteraction()
    }

    fun applyScreenshotProtection(enabled: Boolean) {
        if (enabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * What the recents switcher shows: the neutral app name and a flat colour.
     * Never the alias, and certainly never the real handle.
     */
    private fun applyRecentsDisguise() {
        @Suppress("DEPRECATION")
        setTaskDescription(
            ActivityManager.TaskDescription(
                getString(R.string.app_name),
                null,
                getColor(R.color.mosaic_ink),
            ),
        )
    }
}
