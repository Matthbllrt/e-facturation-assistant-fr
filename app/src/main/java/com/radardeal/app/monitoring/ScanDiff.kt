package com.radardeal.app.monitoring

import com.radardeal.app.data.local.ListingEntity
import com.radardeal.app.data.local.PricePointEntity
import com.radardeal.app.domain.model.Listing

/** A listing whose price went down between two scans. */
data class PriceDrop(
    val listing: Listing,
    val oldPrice: Double,
    val newPrice: Double,
) {
    val ratio: Double get() = if (oldPrice > 0) (oldPrice - newPrice) / oldPrice else 0.0
}

/**
 * The comparison engine: what changed between what RadarDeal already knew for a watch and what
 * the latest scan returned.
 *
 * This is a pure function on purpose — no database, no clock, no Android — so the "new listing"
 * and "price drop" rules are fully covered by unit tests.
 *
 * Two rules matter most:
 *
 *  1. **The first scan is a baseline.** When `baselineDone` is false every result is stored but
 *     nothing is flagged as new, so creating a watch on a search with 52 results does not fire
 *     52 notifications.
 *  2. **Missing data never erases known data.** If a scan returns a listing without its price
 *     or its photo, the previously stored values are kept rather than overwritten with null.
 */
object ScanDiff {

    data class Outcome(
        /** Rows to upsert. */
        val entities: List<ListingEntity>,
        /** Price observations to append. */
        val pricePoints: List<PricePointEntity>,
        /** Listings seen for the first time — empty during a baseline scan. */
        val newListings: List<Listing>,
        /** Listings whose price decreased since the previous scan. */
        val priceDrops: List<PriceDrop>,
    ) {
        val isEmpty: Boolean get() = entities.isEmpty()
    }

    fun diff(
        watchId: Long,
        known: Map<String, Listing>,
        incoming: List<RawListing>,
        baselineDone: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Outcome {
        val entities = ArrayList<ListingEntity>(incoming.size)
        val pricePoints = ArrayList<PricePointEntity>()
        val newListings = ArrayList<Listing>()
        val priceDrops = ArrayList<PriceDrop>()

        // A single response can repeat an id across pages; keep the first occurrence.
        val seen = HashSet<String>(incoming.size)

        for (raw in incoming) {
            if (raw.itemId.isBlank() || !seen.add(raw.itemId)) continue
            val existing = known[raw.itemId]

            val listing = if (existing == null) {
                buildFresh(watchId, raw, now, flagAsNew = baselineDone).also {
                    if (baselineDone) newListings += it
                }
            } else {
                val (merged, drop) = merge(existing, raw, now)
                if (drop != null) priceDrops += drop
                merged
            }

            entities += listing.toEntityRow()

            val price = listing.price
            val priceChanged = existing?.price != price
            if (price != null && (existing == null || priceChanged)) {
                pricePoints += PricePointEntity(
                    watchId = watchId,
                    itemId = listing.itemId,
                    price = price,
                    recordedAt = now,
                )
            }
        }

        return Outcome(entities, pricePoints, newListings, priceDrops)
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun buildFresh(watchId: Long, raw: RawListing, now: Long, flagAsNew: Boolean) = Listing(
        itemId = raw.itemId,
        watchId = watchId,
        title = raw.title,
        brand = raw.brand,
        size = raw.size,
        condition = raw.condition,
        price = raw.price,
        currency = raw.currency,
        previousPrice = null,
        imageUrl = raw.imageUrl,
        itemUrl = raw.itemUrl,
        firstSeenAt = now,
        lastSeenAt = now,
        isNew = flagAsNew,
        isFavorite = false,
        priceDroppedAt = null,
    )

    /**
     * Folds a fresh observation into a listing RadarDeal already knows, and reports the price
     * drop **caused by this scan** — a drop detected earlier is still visible on the card via
     * [Listing.previousPrice], but it is never announced twice.
     *
     * User-owned state ([Listing.isFavorite], [Listing.firstSeenAt]) and any field the scan did
     * not return are preserved.
     */
    private fun merge(existing: Listing, raw: RawListing, now: Long): Pair<Listing, PriceDrop?> {
        val incomingPrice = raw.price
        val knownPrice = existing.price

        val priceMoved = incomingPrice != null && knownPrice != null && incomingPrice != knownPrice
        val dropped = priceMoved && incomingPrice < knownPrice

        val merged = existing.copy(
            title = raw.title ?: existing.title,
            brand = raw.brand ?: existing.brand,
            size = raw.size ?: existing.size,
            condition = raw.condition ?: existing.condition,
            price = incomingPrice ?: knownPrice,
            currency = raw.currency ?: existing.currency,
            previousPrice = if (priceMoved) knownPrice else existing.previousPrice,
            imageUrl = raw.imageUrl ?: existing.imageUrl,
            itemUrl = raw.itemUrl ?: existing.itemUrl,
            lastSeenAt = now,
            priceDroppedAt = if (dropped) now else existing.priceDroppedAt,
        )

        val drop = if (dropped) {
            PriceDrop(listing = merged, oldPrice = knownPrice!!, newPrice = incomingPrice!!)
        } else {
            null
        }
        return merged to drop
    }

    private fun Listing.toEntityRow(): ListingEntity = ListingEntity(
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
}
