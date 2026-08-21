package app.mosaic.privatevault.data

import app.mosaic.privatevault.core.crypto.Sealer
import app.mosaic.privatevault.data.secure.SecureStore
import app.mosaic.privatevault.sync.api.InstagramApiClient
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstagramApiClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    private object PassthroughSealer : Sealer {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    private lateinit var store: SecureStore
    private lateinit var client: InstagramApiClient

    @Before
    fun setUp() {
        store = SecureStore(File(folder.root, "vault.bin"), PassthroughSealer)
        client = InstagramApiClient(store)
    }

    @Test
    fun `no token means no call is even attempted`() = runTest {
        val result = client.call { me() }

        assertTrue(result is InstagramApiClient.Result.Error)
        assertEquals(
            InstagramApiClient.Failure.AuthenticationRequired,
            (result as InstagramApiClient.Result.Error).failure,
        )
    }

    @Test
    fun `a stored token is reported as present`() {
        assertFalse(client.hasToken())

        client.storeToken("IGQVJ-example-token", expiresInSeconds = 5_184_000L)

        assertTrue(client.hasToken())
        assertEquals("IGQVJ-example-token", client.accessToken())
    }

    @Test
    fun `the token is trimmed on the way in`() {
        client.storeToken("  IGQVJ-token  ", expiresInSeconds = null)
        assertEquals("IGQVJ-token", client.accessToken())
    }

    @Test
    fun `a fresh 60 day token does not need refreshing`() {
        val now = 1_700_000_000_000L
        client.storeToken("t", expiresInSeconds = 60L * 24 * 3_600)

        assertFalse(client.shouldRefresh(now))
    }

    @Test
    fun `a token in its last days needs refreshing`() {
        client.storeToken("t", expiresInSeconds = 60L * 24 * 3_600)
        val almostExpired = System.currentTimeMillis() + 55L * 24 * 3_600 * 1000

        assertTrue(client.shouldRefresh(almostExpired))
    }

    @Test
    fun `a token with no known expiry is never refreshed blindly`() {
        client.storeToken("t", expiresInSeconds = null)

        assertFalse(client.shouldRefresh(System.currentTimeMillis()))
    }

    @Test
    fun `clearing the token removes every trace of the session`() {
        client.storeToken("t", expiresInSeconds = 1_000L)
        client.storeSelfUserId("178414")

        client.clearToken()

        assertFalse(client.hasToken())
        assertNull(client.selfUserId())
        assertNull(client.tokenExpiryMillis())
    }

    @Test
    fun `refresh is a no-op when there is nothing to refresh`() = runTest {
        assertFalse(client.refreshTokenIfNeeded())
    }

    @Test
    fun `the base URL is the documented Graph host and carries no app secret`() {
        assertEquals("https://graph.instagram.com/v23.0/", InstagramApiClient.BASE_URL)
    }
}
