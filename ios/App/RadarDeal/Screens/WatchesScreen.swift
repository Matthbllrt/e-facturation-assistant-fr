import SwiftUI
import RadarDealCore

/// The list of saved searches, with the per-watch actions.
struct WatchesScreen: View {
    @Environment(AppGraph.self) private var graph
    @Environment(RadarCoordinator.self) private var coordinator

    @State private var watches: [Watch] = []
    @State private var counts: [Int64: Int] = [:]
    @State private var editing: Watch?
    @State private var creating = false
    @State private var pendingDeletion: Watch?
    @State private var message: String?
    @State private var scanningID: Int64?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: RD.Space.md) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Veilles").font(.rdDisplayMedium).foregroundStyle(RD.textPrimary)
                        Text(subtitle).font(.rdBodySmall).foregroundStyle(RD.textSecondary)
                    }
                    .padding(.top, RD.Space.xl)

                    if watches.isEmpty {
                        EmptyStateView(
                            systemImage: "eye",
                            title: "Aucune veille",
                            message: "Crée ton premier radar : une marque, un modèle, un prix "
                                + "maximum — ou colle directement une recherche Vinted."
                        ) {
                            PrimaryButton(title: "Créer un radar") { creating = true }
                        }
                    }

                    ForEach(watches) { watch in
                        watchCard(watch)
                    }
                }
                .padding(.horizontal, RD.Space.screen)
                .padding(.bottom, 96)
            }
            .background(RD.background)
            .scrollContentBackground(.hidden)
            .overlay(alignment: .bottomTrailing) {
                if !watches.isEmpty {
                    Button { creating = true } label: {
                        Label("Nouvelle veille", systemImage: "plus")
                            .font(.rdTitle)
                            .foregroundStyle(RD.onAccent)
                            .padding(.horizontal, 20)
                            .frame(height: 56)
                            .background(RD.accent, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .padding(RD.Space.screen)
                }
            }
            .sheet(isPresented: $creating) {
                WatchEditorScreen(existing: nil) { reload() }
            }
            .sheet(item: $editing) { watch in
                WatchEditorScreen(existing: watch) { reload() }
            }
            .alert("Supprimer cette veille ?", isPresented: .constant(pendingDeletion != nil)) {
                Button("Annuler", role: .cancel) { pendingDeletion = nil }
                Button("Supprimer", role: .destructive) {
                    if let target = pendingDeletion {
                        graph.store.deleteWatch(id: target.id)
                        coordinator.forget(target.id)
                        reload()
                    }
                    pendingDeletion = nil
                }
            } message: {
                if let target = pendingDeletion {
                    Text("« \(target.name) » et les \(counts[target.id] ?? 0) annonces qu'elle "
                         + "a collectées seront supprimées définitivement.")
                }
            }
            .overlay(alignment: .bottom) {
                if let message {
                    Text(message)
                        .font(.rdBodySmall)
                        .foregroundStyle(RD.textPrimary)
                        .padding(RD.Space.md)
                        .background(RD.surfaceElevated, in: Capsule())
                        .padding(.bottom, 80)
                        .task {
                            try? await Task.sleep(for: .seconds(3))
                            self.message = nil
                        }
                }
            }
        }
        .task { reload() }
        .onChange(of: coordinator.lastTickAt) { _, _ in reload() }
    }

    private var subtitle: String {
        switch watches.count {
        case 0: return "Aucune recherche enregistrée"
        case 1: return "1 recherche enregistrée"
        default: return "\(watches.count) recherches enregistrées"
        }
    }

    private func watchCard(_ watch: Watch) -> some View {
        RDCard {
            VStack(alignment: .leading, spacing: RD.Space.md) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(watch.name)
                            .font(.rdHeadline)
                            .foregroundStyle(RD.textPrimary)
                            .lineLimit(2)
                        Text(watch.criteriaSummary())
                            .font(.rdBodySmall)
                            .foregroundStyle(RD.textSecondary)
                            .lineLimit(2)
                    }
                    Spacer(minLength: RD.Space.sm)
                    HStack(spacing: 6) {
                        LiveDot(active: watch.isActive)
                        Text(watch.isActive ? "LIVE" : "PAUSE")
                            .font(.rdLabel)
                            .foregroundStyle(watch.isActive ? RD.live : RD.textTertiary)
                    }
                }

                HStack(spacing: RD.Space.sm) {
                    if watch.isUltra { BadgePill(text: "⚡ ULTRA") }
                    BadgePill(
                        text: watch.isUltra
                            ? watch.frequency.descriptionText
                            : Formatters.duration(seconds: watch.intervalSeconds),
                        foreground: watch.isUltra ? RD.accent : RD.textSecondary,
                        background: RD.surfaceElevated
                    )
                    BadgePill(text: "\(counts[watch.id] ?? 0) ANNONCES",
                              foreground: RD.textSecondary, background: RD.surfaceElevated)
                }

                Text("Dernier scan : \(Formatters.relativeTime(watch.lastScanAt))"
                     + (watch.lastScanStatus == .never ? "" : " · \(watch.lastScanStatus.label)"))
                    .font(.rdLabelSmall)
                    .foregroundStyle(RD.textTertiary)

                if watch.lastScanStatus.isProblem {
                    StatusBanner(status: watch.lastScanStatus)
                }

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: RD.Space.sm) {
                        SecondaryButton(
                            title: watch.isActive ? "Pause" : "Activer",
                            systemImage: watch.isActive ? "pause.circle" : "play.circle"
                        ) {
                            graph.store.setActive(id: watch.id, active: !watch.isActive)
                            if !watch.isActive { coordinator.markDueNow(watch.id) }
                            reload()
                        }

                        if scanningID == watch.id {
                            ProgressView().tint(RD.accent).frame(width: 48, height: 48)
                        } else {
                            SecondaryButton(title: "Scanner", systemImage: "arrow.clockwise",
                                            tint: RD.accent) {
                                Task { await scanNow(watch) }
                            }
                        }

                        SecondaryButton(title: "Modifier", systemImage: "pencil") {
                            editing = watch
                        }
                        SecondaryButton(title: "Supprimer", systemImage: "trash",
                                        tint: RD.danger) {
                            pendingDeletion = watch
                        }
                    }
                }
            }
            .padding(RD.Space.lg)
        }
    }

    private func scanNow(_ watch: Watch) async {
        scanningID = watch.id
        defer { scanningID = nil }

        let report = await coordinator.scanNow(watchID: watch.id)
        reload()

        guard let report else { message = "Cette veille est introuvable."; return }
        if report.status.isProblem {
            message = report.status.label
        } else if report.wasBaseline {
            message = "Référence enregistrée : \(report.parsedCount) annonces."
        } else if report.newCount > 0 {
            message = "\(report.newCount) nouvelle\(report.newCount > 1 ? "s" : "") annonce"
                + (report.newCount > 1 ? "s" : "")
        } else if report.parsedCount == 0 {
            message = "Aucun résultat pour cette recherche."
        } else {
            message = "Aucune nouveauté (\(report.parsedCount) annonces analysées)."
        }
    }

    private func reload() {
        watches = graph.store.allWatches()
        counts = Dictionary(
            watches.map { ($0.id, graph.store.listings(watchID: $0.id).count) },
            uniquingKeysWith: { a, _ in a }
        )
    }
}
