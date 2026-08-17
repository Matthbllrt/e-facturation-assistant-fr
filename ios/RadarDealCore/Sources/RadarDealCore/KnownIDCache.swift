import Foundation

/// The set of listing ids RadarDeal already knows, held in memory, per watch.
///
/// This keeps storage off the detection path. The question "have I seen this id before?" is
/// asked once per card on every scan; answering it from SwiftData would mean a fetch and a few
/// hundred object materialisations, several times a minute, for an answer that is almost always
/// "yes, all of them".
///
/// Storage remains the source of truth: the cache is warmed from it once per watch, and every
/// newly accepted id is written back afterwards. The cache only ever *shortcuts* the question.
///
/// Thread safety: a lock rather than an actor, because the hot path is synchronous and called
/// from a scan running off the main thread — making it an actor would force every lookup to
/// become a suspension point, which is precisely the cost this class exists to avoid.
public final class KnownIDCache: @unchecked Sendable {

    private let lock = NSLock()
    private var byWatch: [Int64: Set<String>] = [:]
    private var warmed: Set<Int64> = []

    public init() {}

    public func isWarm(_ watchID: Int64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return warmed.contains(watchID)
    }

    /// Replaces the set for `watchID`. Called once, from the first scan.
    public func warm(_ watchID: Int64, ids: [String]) {
        lock.lock(); defer { lock.unlock() }
        byWatch[watchID] = Set(ids)
        warmed.insert(watchID)
    }

    public func isKnown(_ watchID: Int64, _ itemID: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return byWatch[watchID]?.contains(itemID) ?? false
    }

    /// Returns the ids of `candidates` this cache has never seen, preserving order.
    ///
    /// This is the first operation of every scan. When it returns an empty array — the common
    /// case — the caller can stop immediately without building a single listing.
    public func filterUnknown(_ watchID: Int64, _ candidates: [String]) -> [String] {
        lock.lock(); defer { lock.unlock() }
        guard let known = byWatch[watchID] else { return candidates }
        guard !candidates.isEmpty else { return [] }

        var result: [String] = []
        for id in candidates where !known.contains(id) {
            result.append(id)
        }
        return result
    }

    /// Marks `itemID` as known and reports whether this call is the one that claimed it.
    ///
    /// Returning false means another task already took it, so the caller must not notify again.
    @discardableResult
    public func claim(_ watchID: Int64, _ itemID: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return byWatch[watchID, default: []].insert(itemID).inserted
    }

    public func addAll(_ watchID: Int64, ids: [String]) {
        guard !ids.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        byWatch[watchID, default: []].formUnion(ids)
    }

    public func size(_ watchID: Int64) -> Int {
        lock.lock(); defer { lock.unlock() }
        return byWatch[watchID]?.count ?? 0
    }

    /// Called when a watch is deleted, or when its criteria change and the baseline resets.
    public func forget(_ watchID: Int64) {
        lock.lock(); defer { lock.unlock() }
        byWatch.removeValue(forKey: watchID)
        warmed.remove(watchID)
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        byWatch.removeAll()
        warmed.removeAll()
    }
}
