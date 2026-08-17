import Foundation
import RadarDealCore

/// Decides *when* each watch is scanned, and guarantees that only one scan runs at a time.
///
/// The scheduling rules mirror Android's, with one honest difference: on iOS this loop only
/// runs while the app is in the foreground. See `ios/README.md` — there is no equivalent of a
/// foreground service, and no API that lets a third-party app poll every three seconds from the
/// background. The app says so rather than pretending otherwise.
@MainActor
@Observable
final class RadarCoordinator {

    private(set) var isScanning = false
    private(set) var isRunning = false
    private(set) var lastTickAt: Date?

    private let store: Store
    private let engine: RadarEngine
    private let knownIDs: KnownIDCache

    private var loopTask: Task<Void, Never>?

    /// watchID → date before which the watch must not be scanned again.
    private var nextDueAt: [Int64: Date] = [:]
    /// watchID → current adaptive multiplier applied to the base interval.
    private var idleFactor: [Int64: Double] = [:]
    /// watchID → consecutive-error back-off step.
    private var errorStep: [Int64: Int] = [:]
    /// watchID → date of the last full resync.
    private var lastResyncAt: [Int64: Date] = [:]

    init(store: Store, engine: RadarEngine, knownIDs: KnownIDCache) {
        self.store = store
        self.engine = engine
        self.knownIDs = knownIDs
    }

    // MARK: - Lifecycle

    func start() {
        guard loopTask == nil else { return }
        isRunning = true
        RDLog.debug("radar loop started")

        loopTask = Task { [weak self] in
            while let self, !Task.isCancelled {
                await self.tick()

                guard !Task.isCancelled else { break }
                let sleep = await self.millisUntilNextDue()
                try? await Task.sleep(for: .milliseconds(sleep))
            }
        }
    }

    func stop() {
        loopTask?.cancel()
        loopTask = nil
        isRunning = false
        isScanning = false
        RDLog.debug("radar loop stopped")
    }

    // MARK: - Scanning

    /// Scans whichever watches are due. Re-entrancy is impossible: the only caller is the loop,
    /// and `scanNow` awaits the same actor.
    func tick() async {
        guard !isScanning else { return }

        let due = store.activeWatches()
            // A watch paused on a verification makes no request at all until the user acts.
            .filter { !$0.lastScanStatus.haltsScanning }
            .filter { (nextDueAt[$0.id] ?? .distantPast) <= Date() }
            // Ultra first: its whole purpose is not to queue behind slower watches.
            .sorted { $0.isUltra && !$1.isUltra }

        guard !due.isEmpty else { return }

        isScanning = true
        defer { isScanning = false; lastTickAt = Date() }

        for watch in due {
            guard !Task.isCancelled else { return }
            let resync = shouldResync(watch)
            let report = await engine.scan(watch, fullResync: resync)
            if resync { lastResyncAt[watch.id] = Date() }
            reschedule(watch, report)
        }
    }

    /// "Scanner maintenant" — also the user's way of retrying after a verification pause.
    @discardableResult
    func scanNow(watchID: Int64) async -> ScanReport? {
        guard !isScanning, let watch = store.watch(id: watchID) else { return nil }

        isScanning = true
        defer { isScanning = false; lastTickAt = Date() }

        let report = await engine.scan(watch, fullResync: true)
        lastResyncAt[watchID] = Date()
        reschedule(watch, report)
        return report
    }

    func markDueNow(_ watchID: Int64) {
        nextDueAt[watchID] = .distantPast
        idleFactor[watchID] = nil
        errorStep[watchID] = nil
    }

    func forget(_ watchID: Int64) {
        nextDueAt[watchID] = nil
        idleFactor[watchID] = nil
        errorStep[watchID] = nil
        lastResyncAt[watchID] = nil
        knownIDs.forget(watchID)
    }

    /// Called after a watch's criteria change: its collected ids no longer describe the search.
    func invalidate(_ watchID: Int64) {
        knownIDs.forget(watchID)
        markDueNow(watchID)
    }

    // MARK: - Scheduling

    private func millisUntilNextDue() async -> Int {
        let active = store.activeWatches().filter { !$0.lastScanStatus.haltsScanning }
        guard !active.isEmpty else { return Self.idleSleepMs }

        let soonest = active
            .map { (nextDueAt[$0.id] ?? .distantPast).timeIntervalSinceNow * 1000 }
            .min() ?? Double(Self.idleSleepMs)

        return Int(soonest.clamped(to: Double(Self.minSleepMs)...Double(Self.idleSleepMs)))
    }

    /// A periodic full pass catches whatever the incremental path missed: a listing that
    /// slipped between two scans, or a price change.
    private func shouldResync(_ watch: Watch) -> Bool {
        guard let last = lastResyncAt[watch.id] else { return true }
        return Date().timeIntervalSince(last) >= Self.resyncInterval
    }

    private func reschedule(_ watch: Watch, _ report: ScanReport) {
        // Vinted is explicitly refusing. Stop this watch and tell the user; do not retry.
        if report.status == .needsVerification {
            RDLog.debug("watch \(watch.id) paused pending a Vinted verification")
            store.recordScan(id: watch.id, status: .pausedVerification)
            nextDueAt[watch.id] = .distantFuture
            return
        }

        let delay: TimeInterval
        switch report.status {
        case .rateLimited:
            // 429 gets a much longer pause than an ordinary error.
            let step = bumpError(watch.id)
            delay = min(Self.rateLimitBase * pow(2, Double(step - 1)), Self.maxBackoff)
        case .networkError, .parseError:
            let step = bumpError(watch.id)
            delay = Self.errorBackoff[min(step, Self.errorBackoff.count) - 1]
        case .needsLogin:
            delay = Self.loginRetry
        default:
            errorStep[watch.id] = nil
            delay = adaptiveInterval(watch, report)
        }

        nextDueAt[watch.id] = Date().addingTimeInterval(delay)
    }

    /// The floor while a watch is productive, drifting up while it is quiet, and back to the
    /// floor the instant something new arrives.
    private func adaptiveInterval(_ watch: Watch, _ report: ScanReport) -> TimeInterval {
        let floor = watch.baseInterval
        let ceiling = watch.isUltra ? Self.ultraCeiling : floor * Self.idleCeilingMultiplier

        let factor: Double
        if report.newCount > 0 {
            idleFactor[watch.id] = nil
            factor = 1
        } else {
            let next = min((idleFactor[watch.id] ?? 1) * Self.idleGrowth, ceiling / max(floor, 1))
            idleFactor[watch.id] = next
            factor = next
        }

        return (floor * factor).clamped(to: floor...ceiling)
    }

    private func bumpError(_ watchID: Int64) -> Int {
        let next = min((errorStep[watchID] ?? 0) + 1, Self.errorBackoff.count)
        errorStep[watchID] = next
        return next
    }

    // MARK: - Constants

    /// Ultra never drifts beyond this even when nothing is happening.
    private static let ultraCeiling: TimeInterval = 5
    /// Non-Ultra watches may drift to this multiple of their configured interval.
    private static let idleCeilingMultiplier: Double = 4
    private static let idleGrowth: Double = 1.25
    /// 2.5 s → 5 s → 10 s → 30 s.
    private static let errorBackoff: [TimeInterval] = [2.5, 5, 10, 30]
    private static let rateLimitBase: TimeInterval = 60
    private static let maxBackoff: TimeInterval = 30 * 60
    private static let loginRetry: TimeInterval = 5 * 60
    private static let resyncInterval: TimeInterval = 90
    private static let minSleepMs = 250
    private static let idleSleepMs = 30_000
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
