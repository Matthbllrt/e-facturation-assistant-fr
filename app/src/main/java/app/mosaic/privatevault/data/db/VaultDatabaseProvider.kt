package app.mosaic.privatevault.data.db

import android.content.Context
import androidx.room.Room
import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.data.secure.DatabaseKey
import java.io.File
import java.util.Arrays
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the SQLCipher-backed vault, and turns the two ways that can fail into
 * states the UI can explain instead of a crash.
 */
class VaultDatabaseProvider(
    private val context: Context,
    private val databaseKey: DatabaseKey,
) {

    sealed interface OpenResult {
        data class Opened(val database: MosaicDatabase) : OpenResult

        /**
         * The file exists but will not decrypt or parse. Either the Keystore key
         * is gone (device restored from a backup of another phone, app data
         * cleared, key invalidated) or the file is damaged. Mosaic never wipes
         * it on its own — the user is told, and chooses.
         */
        data class Unreadable(val reason: String) : OpenResult
    }

    @Volatile
    private var instance: MosaicDatabase? = null

    fun databaseFile(): File = context.getDatabasePath(MosaicDatabase.FILE_NAME)

    @Synchronized
    fun open(): OpenResult {
        instance?.let { return OpenResult.Opened(it) }

        if (!loadNativeLibrary()) return OpenResult.Unreadable("sqlcipher_unavailable")

        val passphrase = databaseKey.passphrase()
        return try {
            val database = Room.databaseBuilder(
                context,
                MosaicDatabase::class.java,
                MosaicDatabase.FILE_NAME,
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(*MosaicDatabase.MIGRATIONS)
                // No fallbackToDestructiveMigration: see MosaicDatabase.MIGRATIONS.
                .build()

            // Room opens lazily; force it now so a bad passphrase surfaces here
            // rather than on the first query from a background capture.
            database.openHelper.readableDatabase
            instance = database
            OpenResult.Opened(database)
        } catch (error: Throwable) {
            SafeLog.e("vault.open_failed", error)
            OpenResult.Unreadable(error::class.java.simpleName)
        } finally {
            // The passphrase array is consumed by SQLCipher; wipe our copy.
            Arrays.fill(passphrase, 0)
        }
    }

    @Synchronized
    fun close() {
        instance?.close()
        instance = null
    }

    /**
     * Deletes the vault files. The passphrase and Keystore key are destroyed
     * separately by the erase flow, which is what actually makes any residual
     * bytes unrecoverable.
     */
    @Synchronized
    fun deleteFiles(): Boolean {
        close()
        val base = databaseFile()
        val siblings = listOf(base, File("${base.path}-wal"), File("${base.path}-shm"), File("${base.path}-journal"))
        return siblings.all { !it.exists() || it.delete() }
    }

    companion object {
        @Volatile
        private var nativeState: Boolean? = null

        /**
         * SQLCipher ships its own SQLite build and must be loaded before any
         * open.
         *
         * A failure here is survivable and must not take the process with it:
         * on an ABI the bundled library does not cover, the right outcome is a
         * locked app reporting an unreadable vault, not a crash loop on the
         * launcher icon.
         */
        @Synchronized
        fun loadNativeLibrary(): Boolean {
            nativeState?.let { return it }
            val loaded = try {
                System.loadLibrary("sqlcipher")
                true
            } catch (error: Throwable) {
                SafeLog.e("vault.native_library_unavailable", error)
                false
            }
            nativeState = loaded
            return loaded
        }

        fun isNativeAvailable(): Boolean = nativeState == true
    }
}
