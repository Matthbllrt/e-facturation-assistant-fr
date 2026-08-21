package app.mosaic.privatevault.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(
    entities = [MessageEntity::class],
    version = MosaicDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MosaicDatabase : RoomDatabase() {

    abstract fun messages(): MessageDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "mosaic-vault.db"

        /**
         * Every schema change adds its migration here.
         *
         * Destructive migration is never enabled: losing the vault is a worse
         * outcome than failing to open it, because the messages in it may no
         * longer exist anywhere else. A migration gap therefore surfaces as a
         * recoverable error, not as an empty conversation.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        /** Guards against a migration being forgotten when [VERSION] is bumped. */
        fun migrationsCoverAllVersions(): Boolean {
            if (VERSION == 1) return MIGRATIONS.isEmpty()
            val edges = MIGRATIONS.associateBy { it.startVersion }
            var version = 1
            while (version < VERSION) {
                val next = edges[version] ?: return false
                if (next.endVersion <= version) return false
                version = next.endVersion
            }
            return version == VERSION
        }
    }
}
