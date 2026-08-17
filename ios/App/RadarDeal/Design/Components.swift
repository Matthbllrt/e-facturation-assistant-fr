import SwiftUI
import RadarDealCore

/// The standard elevated panel every screen is built from.
struct RDCard<Content: View>: View {
    var color: Color = RD.surface
    var bordered: Bool = true
    @ViewBuilder var content: Content

    var body: some View {
        content
            .background(color)
            .clipShape(RoundedRectangle(cornerRadius: RD.Radius.card, style: .continuous))
            .overlay {
                if bordered {
                    RoundedRectangle(cornerRadius: RD.Radius.card, style: .continuous)
                        .stroke(RD.outline, lineWidth: 1)
                }
            }
    }
}

/// Small uppercase capsule: NOUVEAU, PRIX ↓, ⚡ ULTRA…
struct BadgePill: View {
    let text: String
    var foreground: Color = RD.accent
    var background: Color = RD.accentSoft

    var body: some View {
        Text(text)
            .font(.rdLabel)
            .kerning(0.9)
            .foregroundStyle(foreground)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(background, in: Capsule())
            .lineLimit(1)
    }
}

/// One of the three big numbers on the dashboard.
struct StatTile: View {
    let value: String
    let label: String
    var accent: Color = RD.textPrimary

    var body: some View {
        RDCard(color: RD.surfaceElevated) {
            VStack(alignment: .leading, spacing: 2) {
                Text(value).font(.rdDisplayMedium).foregroundStyle(accent).lineLimit(1)
                Text(label)
                    .font(.rdLabelSmall)
                    .foregroundStyle(RD.textSecondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 18)
        }
    }
}

/// "DERNIÈRES OPPORTUNITÉS" — the small, wide-tracked section label.
struct SectionHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack {
            Text(title.uppercased())
                .font(.rdLabel)
                .kerning(0.9)
                .foregroundStyle(RD.textSecondary)
            Spacer()
            trailing
        }
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(title: String) {
        self.init(title: title) { EmptyView() }
    }
}

/// Selectable filter capsule used by the Radar feed.
struct RDFilterChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.rdTitleSmall)
                .foregroundStyle(selected ? RD.onAccent : RD.textSecondary)
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(selected ? RD.accent : RD.surfaceElevated, in: Capsule())
                .overlay {
                    if !selected { Capsule().stroke(RD.outline, lineWidth: 1) }
                }
        }
        .buttonStyle(.plain)
        .animation(.easeInOut(duration: 0.18), value: selected)
    }
}

/// The pulsing dot that marks a live watch.
struct LiveDot: View {
    var active: Bool = true
    var size: CGFloat = 8
    @State private var pulsing = false

    var body: some View {
        Circle()
            .fill(active ? RD.live : RD.textTertiary)
            .frame(width: size, height: size)
            .opacity(active ? (pulsing ? 1 : 0.35) : 1)
            .animation(
                active ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true) : nil,
                value: pulsing
            )
            .onAppear { if active { pulsing = true } }
    }
}

/// The one prominent action per screen.
struct PrimaryButton: View {
    let title: String
    var systemImage: String?
    var enabled: Bool = true
    var loading: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if loading {
                    ProgressView().tint(RD.onAccent)
                } else {
                    if let systemImage { Image(systemName: systemImage) }
                    Text(title).font(.rdTitle)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 54)
            .foregroundStyle(enabled ? RD.onAccent : RD.textTertiary)
            .background(enabled ? RD.accent : RD.surfaceElevated, in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled || loading)
    }
}

/// Quieter action: outlined, same geometry as `PrimaryButton`.
struct SecondaryButton: View {
    let title: String
    var systemImage: String?
    var tint: Color = RD.textPrimary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 15)) }
                Text(title).font(.rdTitleSmall)
            }
            .foregroundStyle(tint)
            .padding(.horizontal, 18)
            .frame(minHeight: 48)
            .overlay { Capsule().stroke(RD.outline, lineWidth: 1) }
        }
        .buttonStyle(.plain)
    }
}

/// Full-panel empty state: icon, title, one explanatory line, optional action.
struct EmptyStateView<Action: View>: View {
    let systemImage: String
    let title: String
    let message: String
    @ViewBuilder var action: Action

    var body: some View {
        VStack(spacing: RD.Space.md) {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(RD.surfaceElevated)
                .frame(width: 64, height: 64)
                .overlay {
                    Image(systemName: systemImage)
                        .font(.system(size: 26))
                        .foregroundStyle(RD.accent)
                }
            Text(title).font(.rdHeadline).foregroundStyle(RD.textPrimary)
            Text(message)
                .font(.rdBodySmall)
                .foregroundStyle(RD.textSecondary)
                .multilineTextAlignment(.center)
            action.padding(.top, RD.Space.sm)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, RD.Space.xl)
        .padding(.vertical, RD.Space.xxl)
    }
}

extension EmptyStateView where Action == EmptyView {
    init(systemImage: String, title: String, message: String) {
        self.init(systemImage: systemImage, title: title, message: message) { EmptyView() }
    }
}

/// The honest-error surface. Every failure state has its own copy and, where possible, its own
/// action — never a generic "something went wrong".
struct StatusBanner<Action: View>: View {
    let status: ScanStatus
    @ViewBuilder var action: Action

    private var palette: (Color, Color, String) {
        switch status {
        case .needsLogin: return (RD.accent, RD.accentSoft, "lock.fill")
        case .needsVerification, .pausedVerification:
            return (RD.warning, RD.warning.opacity(0.14), "checkmark.shield.fill")
        case .rateLimited: return (RD.warning, RD.warning.opacity(0.14), "hourglass")
        case .networkError: return (RD.danger, RD.danger.opacity(0.14), "wifi.slash")
        default: return (RD.danger, RD.danger.opacity(0.14), "exclamationmark.triangle.fill")
        }
    }

    var body: some View {
        if status.isProblem {
            let (accent, background, icon) = palette
            RDCard(color: background, bordered: false) {
                HStack(alignment: .top, spacing: RD.Space.md) {
                    Image(systemName: icon).foregroundStyle(accent).font(.system(size: 20))
                    VStack(alignment: .leading, spacing: RD.Space.xs) {
                        Text(status.label).font(.rdTitle).foregroundStyle(RD.textPrimary)
                        Text(status.explanation)
                            .font(.rdBodySmall)
                            .foregroundStyle(RD.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        action.padding(.top, RD.Space.sm)
                    }
                    Spacer(minLength: 0)
                }
                .padding(RD.Space.lg)
            }
        }
    }
}

extension StatusBanner where Action == EmptyView {
    init(status: ScanStatus) {
        self.init(status: status) { EmptyView() }
    }
}
