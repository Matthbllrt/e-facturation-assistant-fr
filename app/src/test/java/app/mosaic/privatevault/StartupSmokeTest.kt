package app.mosaic.privatevault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.mosaic.privatevault.data.db.VaultDatabaseProvider
import app.mosaic.privatevault.domain.model.MosaicSettings
import app.mosaic.privatevault.domain.model.NotificationPolicy
import app.mosaic.privatevault.domain.model.SyncMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * First-launch smoke test.
 *
 * Runs the real [MosaicApp.onCreate] on a simulated Android runtime, which is
 * the closest this build environment gets to installing the APK: there is no
 * KVM here, so no emulator (see docs/TESTING.md). What it does prove is that a
 * cold start does not throw, that the vault comes up locked, and that the
 * privacy defaults are the ones documented.
 *
 * It also covers a real device case: an ABI where the bundled SQLCipher library
 * will not load. On the JVM it never loads, so this test always exercises that
 * degraded path — the app must come up locked and report an unreadable vault
 * rather than crash on the launcher icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MosaicApp::class, sdk = [34], manifest = Config.NONE)
class StartupSmokeTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the application starts without throwing`() {
        // Reaching here at all means MosaicApp.onCreate completed.
        assertTrue(application is MosaicApp)
    }

    @Test
    fun `a missing native library does not crash the app`() {
        assertFalse(VaultDatabaseProvider.loadNativeLibrary())
        assertFalse(VaultDatabaseProvider.isNativeAvailable())
    }

    @Test
    fun `the vault is locked on a cold start`() {
        assertTrue(MosaicGraph.appLock.isLocked.value)
    }

    @Test
    fun `a cold start does not touch the vault`() {
        // Nothing is decrypted before the user authenticates: startup must not
        // open the database or even reach for the Keystore. Anything that did
        // would show up here as a state other than Closed — or, on this JVM,
        // as a KeyStoreException.
        assertEquals(MosaicGraph.VaultState.Closed, MosaicGraph.vaultState.value)
    }

    @Test
    fun `the privacy defaults are the documented ones`() = runTest {
        val settings = MosaicGraph.settings.settings.first()

        assertEquals(MosaicSettings.DEFAULT_ALIAS, settings.alias)
        assertEquals(SyncMode.UNSET, settings.syncMode)
        // No notification at all, and no cooldown, until the user asks.
        assertEquals(NotificationPolicy.NONE, settings.notificationPolicy)
        assertEquals(0L, settings.cooldownSeconds)
        // Screenshot and recents protection is on from the first frame.
        assertTrue(settings.screenshotProtection)
        assertFalse(settings.onboardingComplete)
    }

    @Test
    fun `the notification listener reports no permission before it is granted`() {
        assertFalse(
            app.mosaic.privatevault.sync.notification.MosaicNotificationListener
                .isPermissionGranted(application),
        )
    }
}
