import SwiftUI
import RadarDealCore

/// The large, photo-first card used on the Radar feed.
///
/// The photo carries the card; text is reduced to what a buyer decides on in one second.
/// Everything is defensive: a listing with no photo, no price and no size still renders.
struct ListingCardLarge: View {
    let card: ListingCard
    let onOpen: () -> Void
    let onToggleFavorite: () -> Void

    private var listing: Listing { card.listing }

    var body: some View {
        Button(action: onOpen) {
            RDCard {
                VStack(alignment: .leading, spacing: 0) {
                    ZStack(alignment: .topLeading) {
                        ListingImage(url: listing.imageURL)
                            .aspectRatio(1.08, contentMode: .fill)
                            .frame(maxWidth: .infinity)
                            .clipped()

                        // Bottom scrim keeps the badges readable over any photo.
                        LinearGradient(
                            colors: [.clear, RD.background.opacity(0.8)],
                            startPoint: .center, endPoint: .bottom
                        )

                        HStack(spacing: RD.Space.sm) {
                            if listing.isNew { BadgePill(text: "NOUVEAU") }
                            if listing.hasPriceDrop {
                                BadgePill(text: "PRIX ↓", foreground: RD.priceDrop,
                                          background: RD.priceDrop.opacity(0.14))
                            }
                            Spacer()
                            FavoriteButton(isFavorite: listing.isFavorite,
                                           action: onToggleFavorite)
                        }
                        .padding(RD.Space.md)

                        if card.rating != .none {
                            VStack {
                                Spacer()
                                BadgePill(
                                    text: card.rating.label,
                                    foreground: card.rating == .excellent
                                        ? RD.accent : RD.textPrimary,
                                    background: RD.surface.opacity(0.9)
                                )
                                .padding(RD.Space.md)
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: RD.Space.xs) {
                        Text(listing.displayTitle)
                            .font(.rdTitle)
                            .foregroundStyle(RD.textPrimary)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)

                        if !listing.subtitle().isEmpty {
                            Text(listing.subtitle())
                                .font(.rdBodySmall)
                                .foregroundStyle(RD.textSecondary)
                                .lineLimit(1)
                        }

                        HStack(alignment: .firstTextBaseline, spacing: RD.Space.sm) {
                            Text(Formatters.price(listing.price, currency: listing.currency))
                                .font(.rdHeadline)
                                .foregroundStyle(RD.textPrimary)

                            if listing.hasPriceDrop, let previous = listing.previousPrice {
                                Text(Formatters.price(previous, currency: listing.currency))
                                    .font(.rdBodySmall)
                                    .foregroundStyle(RD.textTertiary)
                                    .strikethrough()
                            }
                        }
                        .padding(.top, RD.Space.sm)

                        if let label = card.discountLabel() {
                            Text("↘ \(label)")
                                .font(.rdTitleSmall)
                                .foregroundStyle(RD.accent)
                        }

                        HStack {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(Formatters.relativeTime(listing.firstSeenAt))
                                Text(card.watchName).lineLimit(1)
                            }
                            .font(.rdLabelSmall)
                            .foregroundStyle(RD.textTertiary)

                            Spacer()

                            if let url = listing.itemURL, let link = URL(string: url) {
                                Link(destination: link) {
                                    HStack(spacing: 6) {
                                        Image(systemName: "arrow.up.right.square")
                                        Text("Voir sur Vinted").font(.rdTitleSmall)
                                    }
                                    .foregroundStyle(RD.accent)
                                    .padding(.horizontal, 14)
                                    .frame(minHeight: 40)
                                    .overlay { Capsule().stroke(RD.outline, lineWidth: 1) }
                                }
                            }
                        }
                        .padding(.top, RD.Space.sm)
                    }
                    .padding(RD.Space.lg)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

/// Denser variant used on the Favoris screen.
struct ListingRowCompact: View {
    let card: ListingCard
    let onOpen: () -> Void
    let onToggleFavorite: () -> Void

    var body: some View {
        Button(action: onOpen) {
            RDCard {
                HStack(spacing: RD.Space.md) {
                    ListingImage(url: card.listing.imageURL)
                        .frame(width: 84, height: 84)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

                    VStack(alignment: .leading, spacing: 3) {
                        Text(card.listing.displayTitle)
                            .font(.rdTitleSmall)
                            .foregroundStyle(RD.textPrimary)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                        if !card.listing.subtitle().isEmpty {
                            Text(card.listing.subtitle())
                                .font(.rdLabelSmall)
                                .foregroundStyle(RD.textSecondary)
                                .lineLimit(1)
                        }
                        Text(Formatters.price(card.listing.price, currency: card.listing.currency))
                            .font(.rdTitle)
                            .foregroundStyle(RD.textPrimary)
                    }

                    Spacer(minLength: 0)

                    FavoriteButton(isFavorite: card.listing.isFavorite, action: onToggleFavorite)
                }
                .padding(RD.Space.md)
            }
        }
        .buttonStyle(.plain)
    }
}

struct FavoriteButton: View {
    let isFavorite: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: isFavorite ? "heart.fill" : "heart")
                .font(.system(size: 17))
                .foregroundStyle(isFavorite ? RD.danger : RD.textPrimary)
                .frame(width: 38, height: 38)
                .background(RD.background.opacity(0.7), in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isFavorite ? "Retirer des favoris" : "Ajouter aux favoris")
    }
}

/// Photo slot. A missing or unreachable image degrades to a neutral placeholder rather than an
/// empty hole — Vinted photos expire when a listing is removed, and that must not look broken.
struct ListingImage: View {
    let url: String?

    var body: some View {
        if let url, let link = URL(string: url) {
            AsyncImage(url: link) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().scaledToFill()
                case .empty:
                    RD.surfaceElevated
                default:
                    placeholder
                }
            }
        } else {
            placeholder
        }
    }

    private var placeholder: some View {
        RD.surfaceElevated.overlay {
            Image(systemName: "photo")
                .font(.system(size: 26))
                .foregroundStyle(RD.textTertiary)
        }
    }
}
