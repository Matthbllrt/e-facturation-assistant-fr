package com.radardeal.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.radardeal.app.data.local.RadarDatabase
import com.radardeal.app.data.repository.WatchRepository
import com.radardeal.app.domain.model.ScanFrequency
import com.radardeal.app.domain.model.Watch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * Ultra is a single slot, enforced in the database rather than in the UI, so that no sequence
 * of screens or a race between them can end with two watches both polling every three seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UltraSlotTest {

    private lateinit var database: RadarDatabase
    private lateinit var watches: WatchRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RadarDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(Executor(Runnable::run))
            .build()
        watches = WatchRepository(database.watchDao(), database.listingDao())
    }

    @After
    fun tearDown() = database.close()

    private suspend fun newWatch(name: String, ultra: Boolean = false): Long =
        watches.create(Watch(name = name, keyword = name, isUltra = ultra))

    @Test
    fun `a fresh install has no ultra watch`() = runTest {
        newWatch("A")
        assertThat(watches.getUltraWatch()).isNull()
    }

    @Test
    fun `granting ultra marks exactly one watch`() = runTest {
        val a = newWatch("A")
        newWatch("B")

        watches.grantUltra(a)

        assertThat(watches.getUltraWatch()?.id).isEqualTo(a)
        assertThat(watches.getAll().count { it.isUltra }).isEqualTo(1)
    }

    @Test
    fun `granting ultra to a second watch transfers the slot`() = runTest {
        val a = newWatch("A")
        val b = newWatch("B")

        watches.grantUltra(a)
        watches.grantUltra(b)

        assertThat(watches.getUltraWatch()?.id).isEqualTo(b)
        assertThat(watches.getAll().count { it.isUltra }).isEqualTo(1)
        assertThat(watches.getWatch(a)!!.isUltra).isFalse()
    }

    @Test
    fun `creating several watches as ultra still leaves only one holder after a grant`() = runTest {
        // Even if rows are somehow written with the flag set directly, the grant reconciles.
        newWatch("A", ultra = true)
        newWatch("B", ultra = true)
        val c = newWatch("C", ultra = true)

        watches.grantUltra(c)

        assertThat(watches.getAll().count { it.isUltra }).isEqualTo(1)
        assertThat(watches.getUltraWatch()?.id).isEqualTo(c)
    }

    @Test
    fun `revoking ultra leaves the slot empty`() = runTest {
        val a = newWatch("A")
        watches.grantUltra(a)
        watches.revokeUltra(a)

        assertThat(watches.getUltraWatch()).isNull()
    }

    @Test
    fun `deleting the ultra watch frees the slot`() = runTest {
        val a = newWatch("A")
        watches.grantUltra(a)
        watches.delete(a)

        assertThat(watches.getUltraWatch()).isNull()
    }

    @Test
    fun `an ultra watch reports the ultra frequency and interval`() = runTest {
        val a = newWatch("A")
        watches.grantUltra(a)

        val watch = watches.getWatch(a)!!
        assertThat(watch.frequency).isEqualTo(ScanFrequency.ULTRA)
        assertThat(watch.baseIntervalMs).isEqualTo(ScanFrequency.ULTRA.millis)
    }

    @Test
    fun `a normal watch reports the preset matching its interval`() = runTest {
        val id = watches.create(
            Watch(name = "Standard", keyword = "x", intervalSeconds = ScanFrequency.STANDARD.seconds),
        )
        val watch = watches.getWatch(id)!!

        assertThat(watch.frequency).isEqualTo(ScanFrequency.STANDARD)
        assertThat(watch.baseIntervalMs).isEqualTo(15_000L)
    }

    @Test
    fun `the frequency ladder is ordered fastest to slowest`() {
        val seconds = ScanFrequency.entries.map { it.seconds }
        assertThat(seconds).isInOrder()
        assertThat(ScanFrequency.ULTRA.seconds).isEqualTo(3)
        assertThat(ScanFrequency.FAST.seconds).isEqualTo(5)
        assertThat(ScanFrequency.STANDARD.seconds).isEqualTo(15)
        assertThat(ScanFrequency.ECO.seconds).isEqualTo(30)
    }

    @Test
    fun `fromSeconds never resolves to ultra`() {
        // Ultra is granted through its own path, never inferred from an interval.
        assertThat(ScanFrequency.fromSeconds(1)).isNotEqualTo(ScanFrequency.ULTRA)
        assertThat(ScanFrequency.fromSeconds(3)).isEqualTo(ScanFrequency.FAST)
        assertThat(ScanFrequency.fromSeconds(15)).isEqualTo(ScanFrequency.STANDARD)
        assertThat(ScanFrequency.fromSeconds(30)).isEqualTo(ScanFrequency.ECO)
    }
}
