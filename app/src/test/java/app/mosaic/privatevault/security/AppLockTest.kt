package app.mosaic.privatevault.security

import app.mosaic.privatevault.core.time.Clock
import app.mosaic.privatevault.domain.model.AutoLockDelay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {

    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMillis(): Long = now

        fun advance(millis: Long) {
            now += millis
        }
    }

    @Test
    fun `the vault starts locked`() {
        assertTrue(AppLock(TestClock()).isLocked.value)
    }

    @Test
    fun `unlocking opens the vault`() {
        val lock = AppLock(TestClock())
        lock.unlock()
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun `the panic button locks immediately`() {
        val lock = AppLock(TestClock())
        lock.unlock()
        lock.lock()
        assertTrue(lock.isLocked.value)
    }

    @Test
    fun `immediate auto-lock closes the vault as soon as it leaves the foreground`() {
        val lock = AppLock(TestClock())
        lock.unlock()
        lock.noteBackgrounded()

        assertTrue(lock.refreshOnForeground(AutoLockDelay.IMMEDIATE))
    }

    @Test
    fun `a short absence within the delay keeps the vault open`() {
        val clock = TestClock()
        val lock = AppLock(clock)
        lock.unlock()
        lock.noteBackgrounded()
        clock.advance(10_000L)

        assertFalse(lock.refreshOnForeground(AutoLockDelay.MINUTE_1))
    }

    @Test
    fun `time spent in the background counts fully`() {
        val clock = TestClock()
        val lock = AppLock(clock)
        lock.unlock()
        lock.noteBackgrounded()
        clock.advance(90_000L)

        assertTrue(lock.refreshOnForeground(AutoLockDelay.MINUTE_1))
        assertTrue(lock.isLocked.value)
    }

    @Test
    fun `never means never`() {
        val clock = TestClock()
        val lock = AppLock(clock)
        lock.unlock()
        lock.noteBackgrounded()
        clock.advance(30L * 24 * 3_600_000L)

        assertFalse(lock.refreshOnForeground(AutoLockDelay.NEVER))
    }

    @Test
    fun `idling in the foreground eventually locks`() {
        val clock = TestClock()
        val lock = AppLock(clock)
        lock.unlock()
        clock.advance(20_000L)

        assertTrue(lock.refreshOnIdle(AutoLockDelay.SECONDS_15))
    }

    @Test
    fun `interaction resets the idle timer`() {
        val clock = TestClock()
        val lock = AppLock(clock)
        lock.unlock()
        clock.advance(12_000L)
        lock.noteInteraction()
        clock.advance(10_000L)

        assertFalse(lock.refreshOnIdle(AutoLockDelay.SECONDS_15))
    }

    @Test
    fun `an already locked vault stays locked`() {
        assertTrue(AppLock(TestClock()).refreshOnForeground(AutoLockDelay.NEVER))
    }
}
