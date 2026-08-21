package app.mosaic.privatevault.domain

import app.mosaic.privatevault.domain.cooldown.CooldownPolicy
import app.mosaic.privatevault.domain.model.Cooldown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CooldownPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a disabled cooldown never becomes ready`() {
        assertEquals(
            CooldownPolicy.State.Idle,
            CooldownPolicy.state(now, lastActivityMillis = now - 3_600_000L, cooldown = Cooldown.Off),
        )
    }

    @Test
    fun `with no activity there is nothing to clean up`() {
        assertEquals(
            CooldownPolicy.State.Idle,
            CooldownPolicy.state(now, lastActivityMillis = null, cooldown = Cooldown.Minutes5),
        )
    }

    @Test
    fun `before the delay elapses the remaining time is reported`() {
        val state = CooldownPolicy.state(
            nowMillis = now,
            lastActivityMillis = now - 60_000L,
            cooldown = Cooldown.Minutes5,
        )
        assertEquals(CooldownPolicy.State.Waiting(240_000L), state)
    }

    @Test
    fun `exactly at the deadline it is ready`() {
        val state = CooldownPolicy.state(
            nowMillis = now,
            lastActivityMillis = now - Cooldown.Seconds30.millis,
            cooldown = Cooldown.Seconds30,
        )
        assertEquals(CooldownPolicy.State.Ready, state)
    }

    @Test
    fun `after the deadline it stays ready`() {
        val state = CooldownPolicy.state(
            nowMillis = now,
            lastActivityMillis = now - 3_600_000L,
            cooldown = Cooldown.Minutes15,
        )
        assertEquals(CooldownPolicy.State.Ready, state)
    }

    @Test
    fun `a custom cooldown behaves like the presets`() {
        val fortyMinutes = Cooldown(40 * 60L)
        assertEquals(
            CooldownPolicy.State.Waiting(600_000L),
            CooldownPolicy.state(now, now - 30 * 60_000L, fortyMinutes),
        )
    }

    @Test
    fun `the scheduling delay is never negative`() {
        val delay = CooldownPolicy.scheduleDelayMillis(
            nowMillis = now,
            lastActivityMillis = now - 10 * 60_000L,
            cooldown = Cooldown.Minutes2,
        )
        assertEquals(0L, delay)
    }

    @Test
    fun `no work is scheduled when the cooldown is off`() {
        assertNull(CooldownPolicy.scheduleDelayMillis(now, now, Cooldown.Off))
    }

    @Test
    fun `a new message pushes the deadline out`() {
        val first = CooldownPolicy.readyAt(now - 60_000L, Cooldown.Minutes5)
        val afterNewMessage = CooldownPolicy.readyAt(now, Cooldown.Minutes5)
        assertEquals(60_000L, afterNewMessage!! - first!!)
    }
}
