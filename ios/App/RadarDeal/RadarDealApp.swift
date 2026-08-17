import SwiftUI
import SwiftData
import RadarDealCore

/// Application entry point.
///
/// As on Android, launch does as little as possible: nothing here opens the network, the
/// WebView or anything that could fail. The object graph is built once and handed down through
/// the environment.
@main
struct RadarDealApp: App {

    @State private var graph = AppGraph()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(graph)
                .environment(graph.coordinator)
                .environment(graph.settings)
                .preferredColorScheme(.dark)
                .tint(RD.accent)
        }
        .modelContainer(graph.store.container)
    }
}

/// RadarDeal's object graph — a hand-written locator rather than a DI framework, for the same
/// reason as on Android: every dependency is a plain singleton with an obvious lifetime.
@MainActor
@Observable
final class AppGraph {

    let settings: SettingsStore
    let store: Store
    let session: VintedSession
    let knownIDs: KnownIDCache
    let notifier: Notifier
    let engine: RadarEngine
    let coordinator: RadarCoordinator

    /// A listing to open as soon as the UI is ready, coming from a notification tap.
    var pendingListing: (watchID: Int64, itemID: String)?

    init() {
        settings = SettingsStore()
        // A store that cannot be created is fatal in the literal sense: there is no app
        // without it. Falling back to an in-memory store keeps the UI alive so the user sees
        // an explanation instead of a launch crash.
        store = (try? Store()) ?? (try! Store(inMemory: true))
        session = VintedSession()
        knownIDs = KnownIDCache()
        notifier = Notifier(settings: settings)

        let fetcher = VintedFetcher(session: session)
        engine = RadarEngine(
            store: store, fetcher: fetcher, notifications: notifier,
            knownIDs: knownIDs, settings: settings
        )
        coordinator = RadarCoordinator(store: store, engine: engine, knownIDs: knownIDs)
    }
}
