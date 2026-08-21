package app.mosaic.privatevault.security

import app.mosaic.privatevault.core.crypto.PinHasher
import app.mosaic.privatevault.core.crypto.Sealer
import app.mosaic.privatevault.core.time.Clock
import app.mosaic.privatevault.data.secure.SecureStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** No encryption: this test is about the throttling and verification logic. */
    private object PassthroughSealer : Sealer {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    private class TestClock(var now: Long = 1_000_000L) : Clock {
        override fun nowMillis(): Long = now
    }

    private lateinit var clock: TestClock
    private lateinit var manager: PinManager

    @Before
    fun setUp() {
        clock = TestClock()
        val store = SecureStore(File(folder.root, "vault.bin"), PassthroughSealer)
        manager = PinManager(store, clock)
    }

    @Test
    fun `no PIN is configured on a fresh install`() {
        assertFalse(manager.isConfigured())
        assertEquals(PinManager.Result.NotConfigured, manager.verify("123456".toCharArray()))
    }

    @Test
    fun `a good PIN is accepted`() {
        assertTrue(manager.setPin("428193".toCharArray()))
        assertEquals(PinManager.Result.Success, manager.verify("428193".toCharArray()))
    }

    @Test
    fun `a short PIN is rejected outright`() {
        assertFalse(manager.setPin("1234".toCharArray()))
        assertFalse(manager.isConfigured())
    }

    @Test
    fun `an all-same PIN is rejected`() {
        assertFalse(manager.setPin("111111".toCharArray()))
    }

    @Test
    fun `a straight run is rejected in both directions`() {
        assertFalse(manager.setPin("123456".toCharArray()))
        assertFalse(manager.setPin("654321".toCharArray()))
    }

    @Test
    fun `a wrong PIN reports the remaining attempts`() {
        manager.setPin("428193".toCharArray())

        assertEquals(PinManager.Result.Wrong(2), manager.verify("000000".toCharArray()))
        assertEquals(PinManager.Result.Wrong(1), manager.verify("000000".toCharArray()))
    }

    @Test
    fun `the fourth wrong PIN locks the user out`() {
        manager.setPin("428193".toCharArray())
        repeat(3) { manager.verify("000000".toCharArray()) }

        val result = manager.verify("000000".toCharArray())

        assertTrue(result is PinManager.Result.LockedOut)
    }

    @Test
    fun `the lockout is refused even with the right PIN until it expires`() {
        manager.setPin("428193".toCharArray())
        repeat(4) { manager.verify("000000".toCharArray()) }

        assertTrue(manager.verify("428193".toCharArray()) is PinManager.Result.LockedOut)

        clock.now += PinManager.MAX_LOCKOUT_MILLIS
        assertEquals(PinManager.Result.Success, manager.verify("428193".toCharArray()))
    }

    @Test
    fun `a successful verification clears the penalty`() {
        manager.setPin("428193".toCharArray())
        manager.verify("000000".toCharArray())
        manager.verify("428193".toCharArray())

        assertEquals(0, manager.failureCount())
    }

    @Test
    fun `the lockout grows and is capped`() {
        assertEquals(0L, manager.lockoutMillisFor(PinManager.FREE_ATTEMPTS - 1))
        assertEquals(PinManager.BASE_LOCKOUT_MILLIS, manager.lockoutMillisFor(PinManager.FREE_ATTEMPTS))
        assertEquals(
            PinManager.BASE_LOCKOUT_MILLIS * 2,
            manager.lockoutMillisFor(PinManager.FREE_ATTEMPTS + 1),
        )
        assertEquals(PinManager.MAX_LOCKOUT_MILLIS, manager.lockoutMillisFor(100))
    }

    @Test
    fun `failures survive a restart of the manager`() {
        val file = File(folder.root, "shared.bin")
        val first = PinManager(SecureStore(file, PassthroughSealer), clock)
        first.setPin("428193".toCharArray())
        repeat(2) { first.verify("000000".toCharArray()) }

        // Killing the app is not a way to reset the penalty.
        val second = PinManager(SecureStore(file, PassthroughSealer), clock)
        assertEquals(2, second.failureCount())
    }

    @Test
    fun `the stored verifier is not the PIN`() {
        val pin = "428193"
        manager.setPin(pin.toCharArray())
        val store = SecureStore(File(folder.root, "vault.bin"), PassthroughSealer)
        val verifier = store.getString(SecureStore.KEY_PIN_VERIFIER)!!

        assertFalse(verifier.contains(pin))
    }

    @Test
    fun `two identical PINs hash differently thanks to the salt`() {
        val a = PinHasher.hash("428193".toCharArray())
        val b = PinHasher.hash("428193".toCharArray())

        assertNotEquals(a, b)
        assertTrue(PinHasher.verify("428193".toCharArray(), a))
        assertTrue(PinHasher.verify("428193".toCharArray(), b))
    }

    @Test
    fun `a malformed verifier does not authenticate anyone`() {
        assertFalse(PinHasher.verify("428193".toCharArray(), "garbage"))
        assertFalse(PinHasher.verify("428193".toCharArray(), ""))
    }
}
