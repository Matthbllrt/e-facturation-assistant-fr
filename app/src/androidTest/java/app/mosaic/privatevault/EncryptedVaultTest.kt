package app.mosaic.privatevault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mosaic.privatevault.core.crypto.KeystoreCrypto
import app.mosaic.privatevault.core.crypto.KeystoreSealer
import app.mosaic.privatevault.data.db.MosaicDatabase
import app.mosaic.privatevault.data.db.VaultDatabaseProvider
import app.mosaic.privatevault.data.repo.ConversationRepository
import app.mosaic.privatevault.data.repo.MessageCapture
import app.mosaic.privatevault.data.secure.DatabaseKey
import app.mosaic.privatevault.data.secure.SecureStore
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing that can only be proved on a real device: that the vault file
 * written to `/data/data/.../databases` is genuinely SQLCipher-encrypted and
 * that the passphrase is really sealed by the hardware Keystore.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest` on a connected device or
 * emulator. See docs/TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedVaultTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var provider: VaultDatabaseProvider
    private lateinit var secureStore: SecureStore
    private lateinit var crypto: KeystoreCrypto

    @Before
    fun setUp() {
        VaultDatabaseProvider.loadNativeLibrary()
        crypto = KeystoreCrypto(alias = "mosaic.test.master")
        secureStore = SecureStore(
            file = File(context.cacheDir, "test-vault.bin"),
            sealer = KeystoreSealer(crypto),
        )
        provider = VaultDatabaseProvider(context, DatabaseKey(secureStore))
        provider.deleteFiles()
    }

    @After
    fun tearDown() {
        provider.deleteFiles()
        secureStore.destroy()
        crypto.destroyKey()
    }

    @Test
    fun theVaultOpensAndStoresMessages() = runBlocking {
        val opened = provider.open()
        assertTrue(opened is VaultDatabaseProvider.OpenResult.Opened)

        val database = (opened as VaultDatabaseProvider.OpenResult.Opened).database
        val repository = ConversationRepository(database.messages())

        repository.record(
            MessageCapture(
                timestamp = System.currentTimeMillis(),
                direction = MessageDirection.INCOMING,
                text = "message de test",
                source = MessageSource.ANDROID_NOTIFICATION,
            ),
        )

        assertEquals(1, repository.messageCount())
    }

    @Test
    fun theDatabaseFileIsNotReadableSqlite() = runBlocking {
        val opened = provider.open() as VaultDatabaseProvider.OpenResult.Opened
        ConversationRepository(opened.database.messages()).record(
            MessageCapture(
                timestamp = System.currentTimeMillis(),
                direction = MessageDirection.INCOMING,
                text = "texte tres secret",
                source = MessageSource.ANDROID_NOTIFICATION,
            ),
        )
        provider.close()

        val bytes = provider.databaseFile().readBytes()
        val asText = bytes.toString(Charsets.ISO_8859_1)

        // A plain SQLite file starts with "SQLite format 3"; an encrypted one
        // starts with random bytes, and the message text is nowhere in it.
        assertFalse(asText.startsWith("SQLite format 3"))
        assertFalse(asText.contains("texte tres secret"))
    }

    @Test
    fun theSameKeyReopensTheVault() = runBlocking {
        val first = provider.open() as VaultDatabaseProvider.OpenResult.Opened
        ConversationRepository(first.database.messages()).record(
            MessageCapture(
                timestamp = System.currentTimeMillis(),
                direction = MessageDirection.INCOMING,
                text = "persistant",
                source = MessageSource.ANDROID_NOTIFICATION,
            ),
        )
        provider.close()

        val reopened = VaultDatabaseProvider(context, DatabaseKey(secureStore)).open()
        assertTrue(reopened is VaultDatabaseProvider.OpenResult.Opened)
        val count = ConversationRepository(
            (reopened as VaultDatabaseProvider.OpenResult.Opened).database.messages(),
        ).messageCount()

        assertEquals(1, count)
    }

    @Test
    fun aDifferentPassphraseCannotOpenTheVault() = runBlocking {
        provider.open()
        provider.close()

        // Simulates the Keystore key being lost: the passphrase no longer
        // matches, and Mosaic reports it rather than wiping anything.
        val otherStore = SecureStore(
            file = File(context.cacheDir, "other-vault.bin"),
            sealer = KeystoreSealer(crypto),
        )
        val result = VaultDatabaseProvider(context, DatabaseKey(otherStore)).open()

        assertTrue(result is VaultDatabaseProvider.OpenResult.Unreadable)
        assertTrue(provider.databaseFile().exists())
        otherStore.destroy()
    }

    @Test
    fun keystoreSealingRoundTrips() {
        val plain = "identifiant reel".toByteArray()

        val sealed = crypto.seal(plain)
        assertFalse(sealed.toString(Charsets.ISO_8859_1).contains("identifiant"))
        assertEquals("identifiant reel", String(crypto.open(sealed)))
    }

    @Test
    fun theDeclaredSchemaVersionMatchesTheDatabase() {
        val opened = provider.open() as VaultDatabaseProvider.OpenResult.Opened
        assertEquals(
            MosaicDatabase.VERSION,
            opened.database.openHelper.readableDatabase.version,
        )
    }
}
