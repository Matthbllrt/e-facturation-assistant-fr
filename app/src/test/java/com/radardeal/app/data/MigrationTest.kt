package com.radardeal.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.radardeal.app.data.local.RadarDatabase
import com.radardeal.app.data.local.WatchEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The upgrade path from RadarDeal 1.0.0.
 *
 * A buyer who already owns the shipped version has veilles, favourites and price history in a
 * v1 database. Adding the Ultra flag must not cost them any of it — which is exactly what
 * `fallbackToDestructiveMigration` would have done, and why it is no longer configured.
 *
 * Rather than depending on Room's asset-based `MigrationTestHelper` (whose schema files are not
 * on a Robolectric unit-test classpath, and which would mean shipping schema JSON inside the
 * APK), this builds a real v1 database from the exported v1 DDL, fills it with realistic user
 * data, then opens it through the production Room builder — migration included — and checks the
 * data survived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    /** The exact v1 schema, copied from app/schemas/…/1.json. */
    private val v1Ddl = listOf(
        "CREATE TABLE IF NOT EXISTS `watches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `keyword` TEXT, `brand` TEXT, `minPrice` REAL, `maxPrice` REAL, " +
            "`sourceUrl` TEXT, `intervalSeconds` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, " +
            "`notifyEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `lastScanAt` INTEGER, " +
            "`lastScanStatus` TEXT NOT NULL, `baselineDone` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `listings` (`watchId` INTEGER NOT NULL, `itemId` TEXT NOT NULL, " +
            "`title` TEXT, `brand` TEXT, `size` TEXT, `condition` TEXT, `price` REAL, " +
            "`currency` TEXT, `previousPrice` REAL, `imageUrl` TEXT, `itemUrl` TEXT, " +
            "`firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, `isNew` INTEGER NOT NULL, " +
            "`isFavorite` INTEGER NOT NULL, `priceDroppedAt` INTEGER, " +
            "PRIMARY KEY(`watchId`, `itemId`), FOREIGN KEY(`watchId`) REFERENCES `watches`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_listings_watchId` ON `listings` (`watchId`)",
        "CREATE INDEX IF NOT EXISTS `index_listings_firstSeenAt` ON `listings` (`firstSeenAt`)",
        "CREATE INDEX IF NOT EXISTS `index_listings_isFavorite` ON `listings` (`isFavorite`)",
        "CREATE INDEX IF NOT EXISTS `index_listings_isNew` ON `listings` (`isNew`)",
        "CREATE TABLE IF NOT EXISTS `price_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`watchId` INTEGER NOT NULL, `itemId` TEXT NOT NULL, `price` REAL NOT NULL, " +
            "`recordedAt` INTEGER NOT NULL, FOREIGN KEY(`watchId`) REFERENCES `watches`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_price_points_watchId_itemId` ON `price_points` (`watchId`, `itemId`)",
        "CREATE INDEX IF NOT EXISTS `index_price_points_recordedAt` ON `price_points` (`recordedAt`)",
    )

    /** Room's own bookkeeping, without which it refuses to open an existing file. */
    private val v1IdentityHash = "223b42e6933c841a2c523f71ac98dd65"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).delete()
    }

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    /** Creates a genuine v1 database file, complete with Room's identity metadata. */
    private fun createV1Database(fill: (SupportSQLiteDatabase) -> Unit) {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                v1Ddl.forEach(db::execSQL)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS room_master_table " +
                        "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                        "VALUES(42, '$v1IdentityHash')",
                )
                fill(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun openMigrated(): RadarDatabase =
        Room.databaseBuilder(context, RadarDatabase::class.java, dbName)
            .addMigrations(RadarDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

    // --- Tests -------------------------------------------------------------------------------

    @Test
    fun `v1 data survives the upgrade to v2`() = runTest {
        createV1Database { db ->
            db.execSQL(
                "INSERT INTO watches (id, name, keyword, brand, minPrice, maxPrice, sourceUrl, " +
                    "intervalSeconds, isActive, notifyEnabled, createdAt, lastScanAt, " +
                    "lastScanStatus, baselineDone) VALUES " +
                    "(1, 'Nike Air Max 95', 'Air Max 95', 'Nike', NULL, 80.0, NULL, 30, 1, 1, " +
                    "1700000000000, 1700000100000, 'OK', 1)",
            )
            db.execSQL(
                "INSERT INTO listings (watchId, itemId, title, brand, size, condition, price, " +
                    "currency, previousPrice, imageUrl, itemUrl, firstSeenAt, lastSeenAt, isNew, " +
                    "isFavorite, priceDroppedAt) VALUES " +
                    "(1, '4821337', 'Air Max 95 OG', 'Nike', '42', 'Très bon état', 39.0, 'EUR', " +
                    "70.0, 'https://img/1.jpg', 'https://www.vinted.fr/items/4821337', " +
                    "1700000000000, 1700000100000, 0, 1, 1700000100000)",
            )
            db.execSQL(
                "INSERT INTO price_points (watchId, itemId, price, recordedAt) VALUES " +
                    "(1, '4821337', 70.0, 1700000000000), (1, '4821337', 39.0, 1700000100000)",
            )
        }

        val db = openMigrated()
        try {
            // The veille is intact, including its criteria and baseline state.
            val watch = db.watchDao().getAll().single()
            assertThat(watch.name).isEqualTo("Nike Air Max 95")
            assertThat(watch.keyword).isEqualTo("Air Max 95")
            assertThat(watch.maxPrice).isEqualTo(80.0)
            assertThat(watch.intervalSeconds).isEqualTo(30)
            assertThat(watch.baselineDone).isTrue()
            // Nobody is upgraded straight into Ultra.
            assertThat(watch.isUltra).isFalse()

            // The favourite and its price history are intact.
            val listing = db.listingDao().getForWatch(1L).single()
            assertThat(listing.isFavorite).isTrue()
            assertThat(listing.price).isEqualTo(39.0)
            assertThat(listing.previousPrice).isEqualTo(70.0)

            val history = db.pricePointDao().getHistory(1L, "4821337")
            assertThat(history.map { it.price }).containsExactly(70.0, 39.0).inOrder()
        } finally {
            db.close()
        }
    }

    @Test
    fun `the migrated database accepts the new Ultra column`() = runTest {
        createV1Database { }

        val db = openMigrated()
        try {
            val id = db.watchDao().insert(
                WatchEntity(
                    id = 0,
                    name = "Après migration",
                    keyword = "test",
                    brand = null,
                    minPrice = null,
                    maxPrice = null,
                    sourceUrl = null,
                    intervalSeconds = 5,
                    isUltra = true,
                    isActive = true,
                    notifyEnabled = true,
                    createdAt = 1L,
                    lastScanAt = null,
                    lastScanStatus = "NEVER",
                    baselineDone = false,
                ),
            )

            assertThat(id).isGreaterThan(0L)
            assertThat(db.watchDao().getUltraWatch()?.name).isEqualTo("Après migration")
        } finally {
            db.close()
        }
    }

    @Test
    fun `many v1 watches all survive`() = runTest {
        createV1Database { db ->
            repeat(25) { i ->
                db.execSQL(
                    "INSERT INTO watches (name, keyword, brand, minPrice, maxPrice, sourceUrl, " +
                        "intervalSeconds, isActive, notifyEnabled, createdAt, lastScanAt, " +
                        "lastScanStatus, baselineDone) VALUES " +
                        "('Veille $i', 'kw$i', NULL, NULL, NULL, NULL, 30, 1, 1, $i, NULL, 'NEVER', 0)",
                )
            }
        }

        val db = openMigrated()
        try {
            assertThat(db.watchDao().getAll()).hasSize(25)
            assertThat(db.watchDao().getUltraWatch()).isNull()
        } finally {
            db.close()
        }
    }
}
