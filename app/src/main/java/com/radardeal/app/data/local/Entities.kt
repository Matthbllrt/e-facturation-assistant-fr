package com.radardeal.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.radardeal.app.domain.model.Listing
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.domain.model.Watch

@Entity(tableName = "watches")
data class WatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val keyword: String?,
    val brand: String?,
    val minPrice: Double?,
    val maxPrice: Double?,
    val sourceUrl: String?,
    val intervalSeconds: Int,
    /** Single-slot fast mode; see [com.radardeal.app.domain.model.ScanFrequency.ULTRA]. */
    val isUltra: Boolean,
    val isActive: Boolean,
    val notifyEnabled: Boolean,
    val createdAt: Long,
    val lastScanAt: Long?,
    /** Stored as the enum name so that an unknown future value degrades instead of crashing. */
    val lastScanStatus: String,
    val baselineDone: Boolean,
)

/**
 * A listing is scoped to its watch: the same Vinted item found by two different veilles is
 * stored twice, so that "new" / "favourite" state and the per-watch median stay independent.
 */
@Entity(
    tableName = "listings",
    primaryKeys = ["watchId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = WatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["watchId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("watchId"),
        Index("firstSeenAt"),
        Index("isFavorite"),
        Index("isNew"),
    ],
)
data class ListingEntity(
    val watchId: Long,
    val itemId: String,
    val title: String?,
    val brand: String?,
    val size: String?,
    val condition: String?,
    val price: Double?,
    val currency: String?,
    val previousPrice: Double?,
    val imageUrl: String?,
    val itemUrl: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val isNew: Boolean,
    val isFavorite: Boolean,
    val priceDroppedAt: Long?,
)

/**
 * Price observations, kept in their own table so that a listing can carry a full history.
 *
 * The foreign key matters: without it, deleting a veille would leave its price points behind
 * forever and the database would grow without bound across the app's lifetime.
 */
@Entity(
    tableName = "price_points",
    foreignKeys = [
        ForeignKey(
            entity = WatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["watchId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["watchId", "itemId"]), Index("recordedAt")],
)
data class PricePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val watchId: Long,
    val itemId: String,
    val price: Double,
    val recordedAt: Long,
)

// --- Mapping -------------------------------------------------------------------------------

fun WatchEntity.toDomain(): Watch = Watch(
    id = id,
    name = name,
    keyword = keyword,
    brand = brand,
    minPrice = minPrice,
    maxPrice = maxPrice,
    sourceUrl = sourceUrl,
    intervalSeconds = intervalSeconds,
    isUltra = isUltra,
    isActive = isActive,
    notifyEnabled = notifyEnabled,
    createdAt = createdAt,
    lastScanAt = lastScanAt,
    lastScanStatus = runCatching { ScanStatus.valueOf(lastScanStatus) }.getOrDefault(ScanStatus.NEVER),
    baselineDone = baselineDone,
)

fun Watch.toEntity(): WatchEntity = WatchEntity(
    id = id,
    name = name,
    keyword = keyword,
    brand = brand,
    minPrice = minPrice,
    maxPrice = maxPrice,
    sourceUrl = sourceUrl,
    intervalSeconds = intervalSeconds,
    isUltra = isUltra,
    isActive = isActive,
    notifyEnabled = notifyEnabled,
    createdAt = createdAt,
    lastScanAt = lastScanAt,
    lastScanStatus = lastScanStatus.name,
    baselineDone = baselineDone,
)

fun ListingEntity.toDomain(): Listing = Listing(
    itemId = itemId,
    watchId = watchId,
    title = title,
    brand = brand,
    size = size,
    condition = condition,
    price = price,
    currency = currency,
    previousPrice = previousPrice,
    imageUrl = imageUrl,
    itemUrl = itemUrl,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    isNew = isNew,
    isFavorite = isFavorite,
    priceDroppedAt = priceDroppedAt,
)

fun Listing.toEntity(): ListingEntity = ListingEntity(
    watchId = watchId,
    itemId = itemId,
    title = title,
    brand = brand,
    size = size,
    condition = condition,
    price = price,
    currency = currency,
    previousPrice = previousPrice,
    imageUrl = imageUrl,
    itemUrl = itemUrl,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    isNew = isNew,
    isFavorite = isFavorite,
    priceDroppedAt = priceDroppedAt,
)
