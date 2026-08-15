package com.radardeal.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.radardeal.app.core.RdLog

@Database(
    entities = [WatchEntity::class, ListingEntity::class, PricePointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RadarDatabase : RoomDatabase() {

    abstract fun watchDao(): WatchDao
    abstract fun listingDao(): ListingDao
    abstract fun pricePointDao(): PricePointDao

    companion object {
        const val NAME = "radardeal.db"

        fun build(context: Context): RadarDatabase =
            Room.databaseBuilder(context.applicationContext, RadarDatabase::class.java, NAME)
                // Foreign keys drive the listing/price cleanup when a watch is deleted.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // v1 has no migrations yet. Destructive fallback guarantees that a future
                // schema change can never leave a buyer with an app that refuses to start.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(object : Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        RdLog.d("Room", "database opened (v${db.version})")
                    }
                })
                .build()
    }
}
