import Foundation
import UserNotifications
import BackgroundTasks
import RadarDealCore

/// All local notifications RadarDeal posts.
///
/// Text first, always: an alert is never delayed to download an image. iOS can attach one
/// later through a notification service extension, but the buzz must not wait for the network.
@MainActor
final class Notifier {

    static let newItemsCategory = "RD_NEW_ITEM"
    static let priceDropCategory = "RD_PRICE_DROP"

    private let center = UNUserNotificationCenter.current()
    private let settings: SettingsStore

    init(settings: SettingsStore) {
        self.settings = settings
    }

    /// Asked once, on the first visit to the radar — never on launch.
    @discardableResult
    func requestAuthorization() async -> Bool {
        do {
            return try await center.requestAuthorization(options: [.alert, .sound, .badge])
        } catch {
            RDLog.error("notification authorization failed: \(error)")
            return false
        }
    }

    private func canPost() async -> Bool {
        let status = await center.notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional
    }

    func notifyNewListings(watch: Watch, listings: [Listing]) async {
        guard !listings.isEmpty, await canPost() else { return }

        for listing in listings.sorted(by: { $0.firstSeenAt > $1.firstSeenAt }).prefix(5) {
            let content = UNMutableNotificationContent()
            content.title = "Nouvelle annonce"
            content.subtitle = watch.name
            content.body = [
                listing.displayTitle,
                Formatters.price(listing.price, currency: listing.currency),
                listing.size.map { "Taille \($0)" },
            ].compactMap { $0 }.joined(separator: "\n")
            content.categoryIdentifier = Self.newItemsCategory
            content.sound = settings.soundEnabled ? .default : nil
            content.threadIdentifier = "watch-\(watch.id)"
            // Tapping the alert opens RadarDeal directly on that listing.
            content.userInfo = ["watchID": watch.id, "itemID": listing.itemID]

            await post(id: "new-\(watch.id)-\(listing.itemID)", content: content)
        }
    }

    func notifyPriceDrops(watch: Watch, drops: [PriceDrop]) async {
        guard !drops.isEmpty, await canPost() else { return }

        for drop in drops.prefix(5) {
            let listing = drop.listing
            let content = UNMutableNotificationContent()
            content.title = "Baisse de prix"
            content.subtitle = watch.name
            content.body = "\(listing.displayTitle)\n"
                + "\(Formatters.price(drop.oldPrice, currency: listing.currency)) → "
                + "\(Formatters.price(drop.newPrice, currency: listing.currency))"
                + "  (-\(Int(drop.ratio * 100)) %)"
            content.categoryIdentifier = Self.priceDropCategory
            content.sound = settings.soundEnabled ? .default : nil
            content.threadIdentifier = "watch-\(watch.id)"
            content.userInfo = ["watchID": watch.id, "itemID": listing.itemID]

            await post(id: "drop-\(watch.id)-\(listing.itemID)", content: content)
        }
    }

    func cancelAll() {
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
    }

    /// Delivered immediately; a nil trigger means "as soon as possible".
    private func post(id: String, content: UNNotificationContent) async {
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        do {
            try await center.add(request)
        } catch {
            RDLog.error("notify(\(id)) refused: \(error)")
        }
    }
}

/// The background catch-up.
///
/// This is **not** the monitoring mechanism — iOS decides when, or whether, a
/// `BGAppRefreshTask` runs, and in practice that is minutes to hours apart, not seconds. It
/// exists so that a user who reopens the app after a while has had at least some chance of
/// being alerted, and so the data is not completely stale.
enum BackgroundRefresh {

    static let taskIdentifier = "com.radardeal.app.refresh"

    static func register(coordinator: @escaping @MainActor () -> RadarCoordinator) {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: taskIdentifier, using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else { return }
            handle(refreshTask, coordinator: coordinator)
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        // The earliest iOS will consider us; it is free to wait much longer.
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            RDLog.debug("could not schedule background refresh: \(error)")
        }
    }

    private static func handle(
        _ task: BGAppRefreshTask,
        coordinator: @escaping @MainActor () -> RadarCoordinator
    ) {
        // Always queue the next one first: a missed reschedule means monitoring stops for good.
        schedule()

        let work = Task { @MainActor in
            await coordinator().tick()
            task.setTaskCompleted(success: true)
        }

        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }
}
