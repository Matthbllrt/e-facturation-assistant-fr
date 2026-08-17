import SwiftUI
import RadarDealCore

/// Saved listings. Favourites survive history clearing and per-watch trimming.
struct FavoritesScreen: View {
    @Environment(AppGraph.self) private var graph

    @State private var cards: [ListingCard] = []
    @State private var openListing: Listing?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: RD.Space.md) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Favoris").font(.rdDisplayMedium).foregroundStyle(RD.textPrimary)
                        Text(subtitle).font(.rdBodySmall).foregroundStyle(RD.textSecondary)
                    }
                    .padding(.top, RD.Space.xl)

                    if cards.isEmpty {
                        EmptyStateView(
                            systemImage: "heart",
                            title: "Aucun favori",
                            message: "Touche le cœur sur une annonce pour la retrouver ici. "
                                + "Les favoris sont conservés même quand l'historique est effacé."
                        )
                    }

                    ForEach(cards) { card in
                        ListingRowCompact(card: card) {
                            openListing = card.listing
                        } onToggleFavorite: {
                            graph.store.setFavorite(
                                watchID: card.listing.watchID,
                                itemID: card.listing.itemID,
                                favorite: false
                            )
                            reload()
                        }
                    }
                }
                .padding(.horizontal, RD.Space.screen)
                .padding(.bottom, RD.Space.xxl)
            }
            .background(RD.background)
            .scrollContentBackground(.hidden)
            .navigationDestination(item: $openListing) { ListingDetailScreen(listing: $0) }
        }
        .task { reload() }
    }

    private var subtitle: String {
        switch cards.count {
        case 0: return "Aucune annonce enregistrée"
        case 1: return "1 annonce enregistrée"
        default: return "\(cards.count) annonces enregistrées"
        }
    }

    private func reload() {
        let names = Dictionary(
            graph.store.allWatches().map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a }
        )
        cards = graph.store.favorites().map {
            ListingCard(listing: $0, watchName: names[$0.watchID] ?? "Veille supprimée")
        }
    }
}
