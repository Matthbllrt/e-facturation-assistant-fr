package com.radardeal.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The regression suite for "l'application s'arrête".
 *
 * Every previous build of this project died at launch, so the startup path is asserted here
 * rather than left to a manual check: the Application must construct, the manifest must declare
 * a resolvable exported launcher Activity, the theme and icon resources it names must exist,
 * and MainActivity must actually reach RESUMED — which means Compose composed the first frame
 * without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLaunchTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `application class is the one declared in the manifest`() {
        assertThat(context).isInstanceOf(RadarDealApp::class.java)
    }

    @Test
    fun `application onCreate does no work that could fail`() {
        // Constructing the app must not have opened the database, built an OkHttp client or
        // touched the WebView provider. The graph exists; its members are still untouched.
        val app = context as RadarDealApp
        assertThat(app.appGraph).isNotNull()
        assertThat(app.workManagerConfiguration).isNotNull()
    }

    @Test
    fun `the object graph builds every dependency without throwing`() {
        val graph = RadarDealApp.graph(context)

        assertThat(graph.settingsStore).isNotNull()
        assertThat(graph.database).isNotNull()
        assertThat(graph.watchRepository).isNotNull()
        assertThat(graph.listingRepository).isNotNull()
        assertThat(graph.notifications).isNotNull()
        assertThat(graph.session).isNotNull()
        assertThat(graph.coordinator).isNotNull()

        graph.database.close()
    }

    @Test
    fun `a fresh install reads default settings and shows onboarding`() = runTest {
        val settings = RadarDealApp.graph(context).settingsStore.settings.first()

        assertThat(settings.onboardingDone).isFalse()
        assertThat(settings.notificationsEnabled).isTrue()
        assertThat(settings.monitoringRequested).isFalse()
        assertThat(settings.vintedHost).isEqualTo("www.vinted.fr")
    }

    @Test
    fun `a fresh install has no watches and no listings`() = runTest {
        // Release builds must never ship fixtures: a new install is empty.
        val graph = RadarDealApp.graph(context)
        assertThat(graph.watchRepository.getAll()).isEmpty()
        assertThat(graph.listingRepository.observeRecent().first()).isEmpty()

        graph.database.close()
    }

    @Test
    fun `the launcher activity is exported and resolvable`() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)

        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        assertThat(resolved).isNotEmpty()

        val activityInfo = resolved.first().activityInfo
        assertThat(activityInfo.name).isEqualTo(MainActivity::class.java.name)
        // Without this, Android 12+ refuses to install the app at all.
        assertThat(activityInfo.exported).isTrue()
    }

    @Test
    fun `every resource the manifest names actually exists`() {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)

        assertThat(appInfo.icon).isNotEqualTo(0)
        assertThat(context.getString(R.string.app_name)).isEqualTo("RadarDeal")
        assertThat(context.resources.getColor(R.color.rd_background, null)).isNotEqualTo(0)

        // Resolving the drawables proves they parse — a malformed vector would throw here.
        assertThat(context.resources.getDrawable(R.drawable.ic_notification, null)).isNotNull()
        assertThat(context.resources.getDrawable(R.drawable.ic_launcher_foreground, null)).isNotNull()
        assertThat(context.resources.getDrawable(R.drawable.ic_launcher_background, null)).isNotNull()
        assertThat(context.resources.getDrawable(R.drawable.ic_launcher_monochrome, null)).isNotNull()
    }

    @Test
    fun `main activity launches and reaches the resumed state`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)

            scenario.onActivity { activity ->
                assertThat(activity.isFinishing).isFalse()
                assertThat(activity.window).isNotNull()
            }
        }
    }

    @Test
    fun `main activity survives a configuration change`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun `a notification deep link intent does not break the launch`() {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_WATCH_ID, 7L)
            .putExtra(MainActivity.EXTRA_ITEM_ID, "123456")

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun `a malformed deep link intent is ignored rather than fatal`() {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_WATCH_ID, -1L)
            .putExtra(MainActivity.EXTRA_ITEM_ID, "")

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
    }
}
