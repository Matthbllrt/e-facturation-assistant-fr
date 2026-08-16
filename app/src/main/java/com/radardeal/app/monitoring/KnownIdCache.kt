package com.radardeal.app.monitoring

import java.util.concurrent.ConcurrentHashMap

/**
 * The set of listing ids RadarDeal already knows, held in memory, per watch.
 *
 * This exists to keep Room off the detection path. The previous engine read the whole
 * `listings` table on **every** scan just to answer "have I seen this id before?" — at a
 * 3-second interval with a few hundred stored listings that is a database round trip and a
 * few hundred object allocations, several times a minute, for an answer that is almost always
 * "yes, all of them".
 *
 * Room remains the source of truth: the cache is warmed from it once per watch, and every
 * newly accepted id is written back asynchronously. The cache only ever *shortcuts* the
 * question, it never decides what is persisted.
 *
 * Thread safety: reads happen on whatever dispatcher a scan runs on and writes can arrive from
 * the WebView's live channel at the same time, so the backing sets are concurrent. A stale
 * "unknown" answer is harmless — the diff downstream is still authoritative and will simply
 * merge into the existing row instead of creating one.
 */
class KnownIdCache {

    private val byWatch = ConcurrentHashMap<Long, MutableSet<String>>()

    /** Watches whose set has been loaded from Room at least once. */
    private val warmed = ConcurrentHashMap.newKeySet<Long>()

    fun isWarm(watchId: Long): Boolean = warmed.contains(watchId)

    /** Replaces the set for [watchId] with [ids]. Called once, from the first scan. */
    fun warm(watchId: Long, ids: Collection<String>) {
        val set = ConcurrentHashMap.newKeySet<String>(maxOf(ids.size * 2, 64))
        set.addAll(ids)
        byWatch[watchId] = set
        warmed.add(watchId)
    }

    fun isKnown(watchId: Long, itemId: String): Boolean =
        byWatch[watchId]?.contains(itemId) == true

    /**
     * Returns the ids of [candidates] that this cache has never seen, preserving order.
     *
     * This is the first operation of every scan. When it returns an empty list — the common
     * case — the caller can stop immediately without building a single listing object.
     */
    fun filterUnknown(watchId: Long, candidates: List<String>): List<String> {
        val known = byWatch[watchId] ?: return candidates
        if (candidates.isEmpty()) return emptyList()
        var result: ArrayList<String>? = null
        for (id in candidates) {
            if (!known.contains(id)) {
                (result ?: ArrayList<String>(4).also { result = it }).add(id)
            }
        }
        return result ?: emptyList()
    }

    /**
     * Marks [itemId] as known and reports whether this call is the one that claimed it.
     *
     * Returning false means another thread — typically the live DOM channel and a polling scan
     * racing on the same listing — already took it, so the caller must not notify again.
     */
    fun claim(watchId: Long, itemId: String): Boolean {
        val set = byWatch.getOrPut(watchId) { ConcurrentHashMap.newKeySet() }
        return set.add(itemId)
    }

    fun addAll(watchId: Long, ids: Collection<String>) {
        if (ids.isEmpty()) return
        byWatch.getOrPut(watchId) { ConcurrentHashMap.newKeySet() }.addAll(ids)
    }

    fun size(watchId: Long): Int = byWatch[watchId]?.size ?: 0

    /** Called when a watch is deleted, or when its criteria change and the baseline resets. */
    fun forget(watchId: Long) {
        byWatch.remove(watchId)
        warmed.remove(watchId)
    }

    fun clear() {
        byWatch.clear()
        warmed.clear()
    }
}
