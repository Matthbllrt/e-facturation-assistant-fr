import SwiftUI
import RadarDealCore

/// The home screen: greeting, radar state, three counters, filter chips and the feed.
/// One vertical scroll — no tabs inside tabs, no hidden drawers.
struct RadarScreen: View {
    let onOpenWatches: () -> Void

    @Environment(AppGraph.self) private var graph
    @Environment(RadarCoordinator.self) private var coordinator
    @Environment(SettingsStore.self) private var settings

    @State private var filter: RadarFilter = .all
    @State private var cards: [ListingCard] = []
    @State private var watches: [Watch] = []
    @State private var editingWatch: Watch?
    @State private var openListing: Listing?

    enum RadarFilter: String, CaseIterable {
        case all = "Tout", new = "Nouveau", deals = "Deals"
        case priceDrop = "Prix ↓", favorites = "Favoris"
    }

    private var ultraWatch: Watch? { watches.first { $0.isUltra && $0.isActive } }
    private var activeCount: Int { watches.filter(\.isActive).count }
    private var newCount: Int { cards.filter { $0.listing.isNew }.count }
    private var dropCount: Int { cards.filter { $0.listing.hasPriceDrop }.count }
    private var problem: ScanStatus? {
        watches.map(\.lastScanStatus).filter(\.isProblem).min { rank($0) < rank($1) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: RD.Space.lg) {
                    header

                    HStack(spacing: RD.Space.md) {
                        StatTile(value: "\(newCount)", label: "Nouvelles annonces",
                                 accent: newCount > 0 ? RD.accent : RD.textPrimary)
                        StatTile(value: "\(activeCount)", label: "Veilles actives")
                        StatTile(value: "\(dropCount)", label: "Baisses de prix",
                                 accent: dropCount > 0 ? RD.priceDrop : RD.textPrimary)
                    }

                    if let problem {
                        StatusBanner(status: problem)
                    }

                    if !watches.isEmpty {
                        monitoringToggle
                        chips
                        SectionHeader(title: "Dernières opportunités") {
                            if newCount > 0 {
                                Button("Tout marquer comme vu") {
                                    graph.store.clearAllNewFlags()
                                    reload()
                                }
                                .font(.rdLabelSmall)
                                .foregroundStyle(RD.accent)
                            }
                        }
                    }

                    content
                }
                .padding(.horizontal, RD.Space.screen)
                .padding(.bottom, RD.Space.xxl)
            }
            .background(RD.background)
            .scrollContentBackground(.hidden)
            .refreshable {
                for watch in graph.store.activeWatches() { coordinator.markDueNow(watch.id) }
                await coordinator.tick()
                reload()
            }
            .navigationDestination(item: $openListing) { listing in
                ListingDetailScreen(listing: listing)
            }
            .sheet(item: $editingWatch) { watch in
                WatchEditorScreen(existing: watch.id == 0 ? nil : watch) { reload() }
            }
        }
        .task {
            // Android 13+ and iOS both require an explicit grant; asked once, here, never at
            // launch and never repeatedly.
            await graph.notifier.requestAuthorization()
            reload()
        }
        .onChange(of: coordinator.lastTickAt) { _, _ in reload() }
    }

    // MARK: - Sections

    private var header: some View {
        VStack(alignment: .leading, spacing: RD.Space.sm) {
            Text("\(greeting) 👋")
                .font(.rdDisplayLarge)
                .foregroundStyle(RD.textPrimary)

            HStack(spacing: RD.Space.sm) {
                LiveDot(active: coordinator.isRunning)
                Text(headline)
                    .font(.rdTitle)
                    .foregroundStyle(coordinator.isRunning ? RD.live : RD.textSecondary)
            }

            Text(subtitle)
                .font(.rdBodySmall)
                .foregroundStyle(RD.textSecondary)
        }
        .padding(.top, RD.Space.xl)
    }

    private var headline: String {
        if coordinator.isRunning && ultraWatch != nil { return "RADAR ULTRA ACTIF" }
        return coordinator.isRunning ? "Radar actif" : "Radar en pause"
    }

    private var subtitle: String {
        if let ultraWatch, coordinator.isRunning {
            return "⚡ \(ultraWatch.name) · balayage continu"
        }
        switch activeCount {
        case 0: return "Aucune recherche surveillée"
        case 1: return "1 recherche surveillée"
        default: return "\(activeCount) recherches surveillées"
        }
    }

    private var monitoringToggle: some View {
        RDCard(color: coordinator.isRunning ? RD.accentSoft : RD.surface, bordered: false) {
            HStack(spacing: RD.Space.md) {
                Image(systemName: coordinator.isRunning ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(RD.accent)

                VStack(alignment: .leading, spacing: 2) {
                    Text(coordinator.isRunning ? "Surveillance en cours" : "Surveillance arrêtée")
                        .font(.rdTitle)
                        .foregroundStyle(RD.textPrimary)
                    Text(
                        coordinator.isRunning
                        ? "RadarDeal scanne tant que l'app est ouverte. iOS suspend la "
                          + "surveillance en arrière-plan."
                        : "Lance la surveillance pour recevoir les nouvelles annonces."
                    )
                    .font(.rdBodySmall)
                    .foregroundStyle(RD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)

                SecondaryButton(
                    title: coordinator.isRunning ? "Arrêter" : "Démarrer",
                    tint: coordinator.isRunning ? RD.danger : RD.accent
                ) {
                    if coordinator.isRunning {
                        settings.monitoringRequested = false
                        coordinator.stop()
                    } else {
                        settings.monitoringRequested = true
                        coordinator.start()
                    }
                }
            }
            .padding(RD.Space.lg)
        }
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: RD.Space.sm) {
                ForEach(RadarFilter.allCases, id: \.self) { option in
                    RDFilterChip(label: option.rawValue, selected: filter == option) {
                        filter = option
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if watches.isEmpty {
            EmptyStateView(
                systemImage: "dot.radiowaves.left.and.right",
                title: "Aucune veille",
                message: "Crée ton premier radar pour surveiller une recherche Vinted "
                    + "et être alerté dès qu'une annonce apparaît."
            ) {
                PrimaryButton(title: "Créer un radar") {
                    editingWatch = Watch(id: 0, name: "")
                }
            }
        } else if filtered.isEmpty {
            EmptyStateView(
                systemImage: "magnifyingglass",
                title: emptyTitle,
                message: emptyMessage
            ) {
                if filter != .all {
                    SecondaryButton(title: "Voir tout") { filter = .all }
                } else {
                    SecondaryButton(title: "Gérer mes veilles", action: onOpenWatches)
                }
            }
        } else {
            ForEach(filtered) { card in
                ListingCardLarge(card: card) {
                    graph.store.clearNewFlag(watchID: card.listing.watchID,
                                             itemID: card.listing.itemID)
                    openListing = card.listing
                } onToggleFavorite: {
                    graph.store.setFavorite(
                        watchID: card.listing.watchID, itemID: card.listing.itemID,
                        favorite: !card.listing.isFavorite
                    )
                    reload()
                }
            }
        }
    }

    // MARK: - Data

    private var filtered: [ListingCard] {
        switch filter {
        case .all: return cards
        case .new: return cards.filter { $0.listing.isNew }
        case .deals:
            return cards.filter { $0.rating != .none }
                .sorted { ($0.medianDiscount ?? 0) > ($1.medianDiscount ?? 0) }
        case .priceDrop:
            return cards.filter { $0.listing.hasPriceDrop }
        case .favorites: return cards.filter { $0.listing.isFavorite }
        }
    }

    private func reload() {
        watches = graph.store.allWatches()
        let names = Dictionary(watches.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
        let listings = graph.store.recentListings()
        let pricesByWatch = Dictionary(grouping: listings, by: \.watchID)
            .mapValues { $0.compactMap(\.price) }

        cards = listings.map { listing in
            let assessment = DealEngine.assess(
                price: listing.price, allPrices: pricesByWatch[listing.watchID] ?? []
            )
            return ListingCard(
                listing: listing,
                watchName: names[listing.watchID] ?? "Veille supprimée",
                rating: assessment.rating,
                medianDiscount: assessment.discount,
                comparableCount: assessment.comparableCount
            )
        }
    }

    private var greeting: String {
        let hour = Calendar.current.component(.hour, from: Date())
        return (5...17).contains(hour) ? "Bonjour" : "Bonsoir"
    }

    /// Lower is more urgent — decides which banner to show when several watches are unhappy.
    private func rank(_ status: ScanStatus) -> Int {
        switch status {
        case .needsLogin: return 0
        case .needsVerification, .pausedVerification: return 1
        case .rateLimited: return 2
        case .networkError: return 3
        case .parseError: return 4
        default: return 99
        }
    }

    private var emptyTitle: String {
        switch filter {
        case .all: return "Aucune annonce pour le moment"
        case .new: return "Aucune nouvelle annonce"
        case .deals: return "Aucun deal détecté"
        case .priceDrop: return "Aucune baisse de prix"
        case .favorites: return "Aucun favori"
        }
    }

    private var emptyMessage: String {
        switch filter {
        case .all:
            return "Dès que le radar trouvera des annonces correspondant à tes veilles, "
                + "elles apparaîtront ici."
        case .new:
            return "Le premier scan d'une veille sert de référence : seules les annonces "
                + "publiées ensuite sont marquées comme nouvelles."
        case .deals:
            return "RadarDeal compare les prix à la médiane des annonces détectées. Il lui "
                + "faut au moins \(DealEngine.minComparables) annonces avec un prix."
        case .priceDrop:
            return "Aucune annonce suivie n'a baissé de prix depuis son premier scan."
        case .favorites:
            return "Touche le cœur sur une annonce pour la garder ici."
        }
    }
}

extension Listing: @retroactive Identifiable {}
extension Watch: @retroactive Identifiable {}
