package app.mosaic.privatevault

import android.content.Context
import app.mosaic.privatevault.core.crypto.KeystoreCrypto
import app.mosaic.privatevault.core.crypto.KeystoreSealer
import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.core.time.Clock
import app.mosaic.privatevault.data.db.VaultDatabaseProvider
import app.mosaic.privatevault.data.prefs.SettingsRepository
import app.mosaic.privatevault.data.repo.ConversationRepository
import app.mosaic.privatevault.data.repo.TargetRepository
import app.mosaic.privatevault.data.secure.DatabaseKey
import app.mosaic.privatevault.data.secure.SecureStore
import app.mosaic.privatevault.domain.notify.NotificationMatcher
import app.mosaic.privatevault.security.AppLock
import app.mosaic.privatevault.security.BiometricGate
import app.mosaic.privatevault.security.PinManager
import app.mosaic.privatevault.sync.api.InstagramApiClient
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hand-rolled service locator.
 *
 * A four-screen, single-purpose app does not need a DI framework, and the whole
 * object graph fitting on one screen is itself a security property: there is one
 * obvious place to check what has access to the vault.
 */
object MosaicGraph {

    sealed interface VaultState {
        data object Closed : VaultState
        data object Open : VaultState

        /** The vault exists but cannot be decrypted. Never auto-wiped. */
        data class Unreadable(val reason: String) : VaultState
    }

    private lateinit var appContext: Context

    val clock: Clock = Clock.System

    val keystoreCrypto: KeystoreCrypto by lazy { KeystoreCrypto() }

    val secureStore: SecureStore by lazy {
        SecureStore(
            file = File(File(appContext.filesDir, "secure").apply { mkdirs() }, "vault.bin"),
            sealer = KeystoreSealer(keystoreCrypto),
        )
    }

    val databaseKey: DatabaseKey by lazy { DatabaseKey(secureStore) }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val target: TargetRepository by lazy { TargetRepository(secureStore) }

    val appLock: AppLock by lazy { AppLock(clock) }

    val pinManager: PinManager by lazy { PinManager(secureStore, clock) }

    val biometricGate: BiometricGate by lazy { BiometricGate() }

    val notificationMatcher: NotificationMatcher by lazy { NotificationMatcher() }

    val api: InstagramApiClient by lazy { InstagramApiClient(secureStore) }

    private val vaultProvider: VaultDatabaseProvider by lazy {
        VaultDatabaseProvider(appContext, databaseKey)
    }

    private val vaultStateFlow = MutableStateFlow<VaultState>(VaultState.Closed)
    val vaultState: StateFlow<VaultState> = vaultStateFlow.asStateFlow()

    @Volatile
    private var conversationRepository: ConversationRepository? = null

    /** Serialises the two destructive flows against each other. */
    private val eraseMutex = Mutex()

    fun install(context: Context) {
        appContext = context.applicationContext
        VaultDatabaseProvider.loadNativeLibrary()
    }

    fun isInstalled(): Boolean = this::appContext.isInitialized

    fun context(): Context = appContext

    /**
     * Opens the vault on demand.
     *
     * Called both from the UI and from the notification listener, which may run
     * with the app otherwise dead — messages arriving while Mosaic has never
     * been opened must still be stored.
     */
    @Synchronized
    fun conversations(): ConversationRepository? {
        conversationRepository?.let { return it }
        return when (val result = vaultProvider.open()) {
            is VaultDatabaseProvider.OpenResult.Opened -> {
                val repository = ConversationRepository(result.database.messages(), clock)
                conversationRepository = repository
                vaultStateFlow.value = VaultState.Open
                repository
            }

            is VaultDatabaseProvider.OpenResult.Unreadable -> {
                vaultStateFlow.value = VaultState.Unreadable(result.reason)
                SafeLog.e("graph.vault_unreadable")
                null
            }
        }
    }

    /**
     * Destroys every local trace: database files, encrypted store, preferences
     * and — decisively — the Keystore master key, without which nothing that
     * survives on the flash is decryptable.
     */
    suspend fun eraseEverything() = eraseMutex.withLock {
        runCatching { conversationRepository?.eraseAll() }
        vaultProvider.deleteFiles()
        conversationRepository = null
        secureStore.destroy()
        settings.clear()
        biometricGate.clearEnrollmentMarker()
        keystoreCrypto.destroyKey()
        vaultStateFlow.value = VaultState.Closed
        appLock.lock()
    }

    /** Erases the conversation but keeps the configuration and the keys. */
    suspend fun eraseConversationOnly() = eraseMutex.withLock {
        conversations()?.eraseAll()
        Unit
    }
}
