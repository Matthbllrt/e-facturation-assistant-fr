package app.mosaic.privatevault.data

import app.mosaic.privatevault.data.db.MosaicDatabase
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migration safety, checked at build time.
 *
 * The failure this guards against is specific and expensive: someone bumps
 * [MosaicDatabase.VERSION] without adding the matching migration, Room throws
 * at open, and the vault — whose contents may exist nowhere else — becomes
 * unreachable. Destructive fallback is deliberately not configured, so the
 * mistake cannot be papered over by silently wiping the user's messages.
 *
 * The behavioural migration test (real SQLCipher file, real upgrade) lives in
 * `androidTest` because it needs a device; see docs/TESTING.md.
 */
class MosaicDatabaseSchemaTest {

    @Test
    fun `every version has a migration path`() {
        assertTrue(
            "MosaicDatabase.VERSION was bumped without adding a Migration",
            MosaicDatabase.migrationsCoverAllVersions(),
        )
    }

    @Test
    fun `migrations are declared in order and cover contiguous versions`() {
        val migrations = MosaicDatabase.MIGRATIONS.sortedBy { it.startVersion }
        migrations.zipWithNext().forEach { (current, next) ->
            assertTrue(
                "gap between schema ${current.endVersion} and ${next.startVersion}",
                current.endVersion == next.startVersion,
            )
        }
    }

    @Test
    fun `the schema is exported so future migrations can be written against it`() {
        val schemas = File("schemas/app.mosaic.privatevault.data.db.MosaicDatabase")
        assertTrue(
            "Room schema export missing; run an assemble first or check exportSchema",
            schemas.isDirectory && schemas.listFiles()?.isNotEmpty() == true,
        )
    }

    @Test
    fun `destructive migration is not available on the database class`() {
        // A direct read of the builder is not possible, so this asserts the
        // intent that the provider encodes: no fallback method is called
        // anywhere in the source.
        val provider = File("src/main/java/app/mosaic/privatevault/data/db/VaultDatabaseProvider.kt")
        assertTrue(provider.isFile)
        assertTrue(
            "destructive migration would silently delete the vault",
            !provider.readText().contains("fallbackToDestructiveMigration("),
        )
    }
}
