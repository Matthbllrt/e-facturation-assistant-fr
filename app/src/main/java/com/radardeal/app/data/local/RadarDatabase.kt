package com.radardeal.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.radardeal.app.core.RdLog

@Database(
    entities = [WatchEntity::class, ListingEntity::class, PricePointEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class RadarDatabase : RoomDatabase() {

    abstract fun watchDao(): WatchDao
    abstract fun listingDao(): ListingDao
    abstract fun pricePointDao(): PricePointDao

    companion object {
        const val NAME = "radardeal.db"

        /**
         * v1 → v2: adds the Ultra flag.
         *
         * A plain `ALTER TABLE ... ADD COLUMN` with a default: every existing veille, listing,
         * favourite and price point is preserved untouched. Destructive fallback is
         * deliberately **not** configured — a buyer updating the app must never silently lose
         * the searches and favourites they built up.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watches ADD COLUMN isUltra INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): RadarDatabase =
            Room.databaseBuilder(context.applicationContext, RadarDatabase::class.java, NAME)
                // Foreign keys drive the listing/price cleanup when a watch is deleted.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        RdLog.d("Room", "database opened (v${db.version})")
                    }
                })
                .build()
    }
}
