import SwiftUI
import RadarDealCore

/// Full view of one listing: photo, price, deal context and the recorded price history.
struct ListingDetailScreen: View {
    let listing: Listing

    @Environment(AppGraph.self) private var graph

    @State private var current: Listing?
    @State private var assessment = DealEngine.Assessment.none
    @State private var history: [PricePoint] = []
    @State private var watchName = ""

    private var shown: Listing { current ?? listing }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: RD.Space.lg) {
                ListingImage(url: shown.imageURL)
                    .aspectRatio(1, contentMode: .fill)
                    .frame(maxWidth: .infinity)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: RD.Radius.card, style: .continuous))

                HStack(spacing: RD.Space.sm) {
                    if shown.isNew { BadgePill(text: "NOUVEAU") }
                    if shown.hasPriceDrop {
                        BadgePill(text: "PRIX ↓", foreground: RD.priceDrop,
                                  background: RD.priceDrop.opacity(0.14))
                    }
                    if assessment.rating != .none { BadgePill(text: assessment.rating.label) }
                }

                Text(shown.displayTitle).font(.rdDisplayMedium).foregroundStyle(RD.textPrimary)

                if !shown.subtitle().isEmpty {
                    Text(shown.subtitle()).font(.rdBody).foregroundStyle(RD.textSecondary)
                }

                HStack(alignment: .firstTextBaseline, spacing: RD.Space.md) {
                    Text(Formatters.price(shown.price, currency: shown.currency))
                        .font(.rdDisplayMedium)
                        .foregroundStyle(RD.textPrimary)

                    if shown.hasPriceDrop, let previous = shown.previousPrice {
                        VStack(alignment: .leading) {
                            Text(Formatters.price(previous, currency: shown.currency))
                                .font(.rdBodySmall)
                                .foregroundStyle(RD.textTertiary)
                                .strikethrough()
                            if let ratio = shown.priceDropRatio {
                                Text("-\(Int(ratio * 100)) %")
                                    .font(.rdTitleSmall)
                                    .foregroundStyle(RD.priceDrop)
                            }
                        }
                    }
                }

                if let discount = assessment.discount,
                   assessment.comparableCount >= DealEngine.minComparables {
                    RDCard(color: RD.accentSoft, bordered: false) {
                        VStack(alignment: .leading, spacing: RD.Space.xs) {
                            Text("\(Int(discount * 100)) % sous la médiane des annonces détectées")
                                .font(.rdTitle)
                                .foregroundStyle(RD.accent)
                            Text("Comparé aux \(assessment.comparableCount) annonces détectées "
                                 + "par la veille « \(watchName) ». Ce n'est pas un prix de "
                                 + "marché : c'est la médiane de ce que RadarDeal a vu passer.")
                                .font(.rdBodySmall)
                                .foregroundStyle(RD.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(RD.Space.lg)
                    }
                }

                SectionHeader(title: "Historique")

                RDCard {
                    VStack(alignment: .leading, spacing: RD.Space.sm) {
                        detailRow("Veille", watchName)
                        detailRow("Première détection", absolute(shown.firstSeenAt))
                        detailRow("Vue pour la dernière fois",
                                  Formatters.relativeTime(shown.lastSeenAt))

                        if history.count > 1 {
                            Text("Prix relevés").font(.rdTitleSmall)
                                .foregroundStyle(RD.textPrimary)
                                .padding(.top, RD.Space.xs)
                            ForEach(history.reversed().prefix(8), id: \.recordedAt) { point in
                                detailRow(
                                    absolute(point.recordedAt),
                                    Formatters.price(point.price, currency: shown.currency)
                                )
                            }
                        }
                    }
                    .padding(RD.Space.lg)
                }
            }
            .padding(.horizontal, RD.Space.screen)
            .padding(.bottom, RD.Space.xxl)
        }
        .background(RD.background)
        .scrollContentBackground(.hidden)
        .safeAreaInset(edge: .bottom) {
            if let url = shown.itemURL, let link = URL(string: url) {
                Link(destination: link) {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.up.right.square")
                        Text("Voir sur Vinted").font(.rdTitle)
                    }
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .foregroundStyle(RD.onAccent)
                    .background(RD.accent, in: Capsule())
                }
                .padding(.horizontal, RD.Space.screen)
                .padding(.vertical, RD.Space.md)
                .background(RD.background)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    graph.store.setFavorite(watchID: shown.watchID, itemID: shown.itemID,
                                            favorite: !shown.isFavorite)
                    reload()
                } label: {
                    Image(systemName: shown.isFavorite ? "heart.fill" : "heart")
                        .foregroundStyle(shown.isFavorite ? RD.danger : RD.textPrimary)
                }
            }
        }
        .task {
            // Opening a listing is what marks it as seen.
            graph.store.clearNewFlag(watchID: listing.watchID, itemID: listing.itemID)
            reload()
        }
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.rdBodySmall).foregroundStyle(RD.textSecondary)
            Spacer()
            Text(value).font(.rdTitleSmall).foregroundStyle(RD.textPrimary)
        }
    }

    private func reload() {
        current = graph.store.listings(watchID: listing.watchID)
            .first { $0.itemID == listing.itemID }
        watchName = graph.store.watch(id: listing.watchID)?.name ?? "Veille supprimée"
        history = graph.store.priceHistory(watchID: listing.watchID, itemID: listing.itemID)
        assessment = DealEngine.assess(
            price: shown.price, allPrices: graph.store.prices(watchID: listing.watchID)
        )
    }

    private func absolute(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = "d MMM yyyy · HH:mm"
        return formatter.string(from: date)
    }
}
