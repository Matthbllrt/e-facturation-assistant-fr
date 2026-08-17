import SwiftUI
import RadarDealCore

/// Four tabs, deliberately capped at four, exactly as on Android.
struct RootView: View {
    @Environment(AppGraph.self) private var graph
    @Environment(SettingsStore.self) private var settings

    @State private var showOnboarding = false
    @State private var selection = Tab.radar

    enum Tab: Hashable { case radar, watches, favorites, settings }

    var body: some View {
        TabView(selection: $selection) {
            RadarScreen(onOpenWatches: { selection = .watches })
                .tabItem { Label("Radar", systemImage: "dot.radiowaves.left.and.right") }
                .tag(Tab.radar)

            WatchesScreen()
                .tabItem { Label("Veilles", systemImage: "eye") }
                .tag(Tab.watches)

            FavoritesScreen()
                .tabItem { Label("Favoris", systemImage: "heart.fill") }
                .tag(Tab.favorites)

            SettingsScreen()
                .tabItem { Label("Réglages", systemImage: "gearshape.fill") }
                .tag(Tab.settings)
        }
        .background(RD.background)
        .task {
            showOnboarding = !settings.onboardingDone
            if settings.monitoringRequested { graph.coordinator.start() }
            BackgroundRefresh.schedule()
        }
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingScreen {
                settings.onboardingDone = true
                showOnboarding = false
            }
        }
    }
}
