import SwiftUI
import RadarDealCore

/// Create or edit a veille. One screen, two ways in: type what you are looking for, or paste a
/// Vinted search URL — the powerful route, because it keeps every advanced filter.
struct WatchEditorScreen: View {
    let existing: Watch?
    let onDone: () -> Void

    @Environment(AppGraph.self) private var graph
    @Environment(RadarCoordinator.self) private var coordinator
    @Environment(SettingsStore.self) private var settings
    @Environment(\.dismiss) private var dismiss

    @State private var mode: Mode = .criteria
    @State private var name = ""
    @State private var keyword = ""
    @State private var brand = ""
    @State private var maxPrice = ""
    @State private var sourceURL = ""
    @State private var frequency: ScanFrequency = .standard
    @State private var urlError: String?
    @State private var ultraConflict: Watch?

    enum Mode: String, CaseIterable { case criteria = "Critères", url = "URL Vinted" }

    private var canSave: Bool {
        switch mode {
        case .criteria: return !keyword.trimmed.isEmpty || !brand.trimmed.isEmpty
        case .url: return !sourceURL.trimmed.isEmpty && urlError == nil
        }
    }

    private var effectiveName: String {
        let trimmed = name.trimmed
        if !trimmed.isEmpty { return trimmed }
        switch mode {
        case .criteria:
            let derived = [brand.trimmed, keyword.trimmed].filter { !$0.isEmpty }
                .joined(separator: " ")
            return derived.isEmpty ? "Ma veille" : derived
        case .url:
            return "Recherche Vinted"
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: RD.Space.lg) {
                    Text(existing == nil ? "Que cherches-tu ?" : "Modifier la veille")
                        .font(.rdDisplayMedium)
                        .foregroundStyle(RD.textPrimary)

                    HStack(spacing: RD.Space.sm) {
                        ForEach(Mode.allCases, id: \.self) { option in
                            RDFilterChip(label: option.rawValue, selected: mode == option) {
                                mode = option
                            }
                        }
                    }

                    if mode == .criteria {
                        field("Mot clé", text: $keyword, placeholder: "Air Max 95")
                        field("Marque", text: $brand, placeholder: "Nike")
                        field("Prix maximum", text: $maxPrice, placeholder: "80",
                              keyboard: .numberPad)
                    } else {
                        field("URL de recherche Vinted", text: $sourceURL,
                              placeholder: "https://www.vinted.fr/catalog?search_text=...",
                              error: urlError, keyboard: .URL)
                            .onChange(of: sourceURL) { _, value in validate(value) }

                        RDCard(color: RD.surfaceElevated, bordered: false) {
                            VStack(alignment: .leading, spacing: RD.Space.xs) {
                                Text("Pourquoi c'est la meilleure option")
                                    .font(.rdTitleSmall)
                                    .foregroundStyle(RD.textPrimary)
                                Text("Lance ta recherche sur Vinted avec tous les filtres que "
                                     + "tu veux (catégorie, taille, état, couleur, marque, "
                                     + "prix), puis copie l'adresse de la page de résultats. "
                                     + "RadarDeal surveillera exactement cette recherche.")
                                    .font(.rdBodySmall)
                                    .foregroundStyle(RD.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(RD.Space.lg)
                        }
                    }

                    field("Nom de la veille (optionnel)", text: $name,
                          placeholder: effectiveName)

                    SectionHeader(title: "Fréquence")

                    VStack(spacing: RD.Space.sm) {
                        ForEach(ScanFrequency.allCases, id: \.self) { option in
                            frequencyRow(option)
                        }
                    }

                    Text(
                        frequency == .ultra
                        ? "Le mode Ultra ne peut être actif que sur une seule veille à la "
                          + "fois. Sur iOS il ne fonctionne que lorsque RadarDeal est ouvert "
                          + "au premier plan — le système suspend l'application en "
                          + "arrière-plan, aucune application ne peut l'en empêcher."
                        : "Sur iOS, la surveillance ne tourne que lorsque RadarDeal est "
                          + "ouvert. En arrière-plan, iOS n'autorise qu'un rafraîchissement "
                          + "occasionnel, à sa discrétion."
                    )
                    .font(.rdLabelSmall)
                    .foregroundStyle(RD.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, RD.Space.screen)
                .padding(.vertical, RD.Space.lg)
            }
            .background(RD.background)
            .scrollContentBackground(.hidden)
            .safeAreaInset(edge: .bottom) {
                PrimaryButton(
                    title: existing == nil ? "Activer le radar" : "Enregistrer",
                    systemImage: "dot.radiowaves.left.and.right",
                    enabled: canSave,
                    action: save
                )
                .padding(.horizontal, RD.Space.screen)
                .padding(.vertical, RD.Space.md)
                .background(RD.background)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Fermer") { dismiss() }.foregroundStyle(RD.textSecondary)
                }
            }
            .alert(
                "Le mode Ultra est déjà utilisé",
                isPresented: .constant(ultraConflict != nil)
            ) {
                Button("Annuler", role: .cancel) {
                    ultraConflict = nil
                    frequency = ScanFrequency.from(seconds: settings.defaultIntervalSeconds)
                }
                Button("Transférer") {
                    frequency = .ultra
                    ultraConflict = nil
                }
            } message: {
                if let holder = ultraConflict {
                    Text("Le mode Ultra ne peut être actif que sur une seule veille à la "
                         + "fois.\n\nIl est actuellement sur « \(holder.name) ». Veux-tu le "
                         + "transférer à cette veille ?")
                }
            }
        }
        .task { load() }
    }

    // MARK: - Pieces

    private func frequencyRow(_ option: ScanFrequency) -> some View {
        Button {
            select(option)
        } label: {
            RDCard(color: frequency == option ? RD.accentSoft : RD.surface, bordered: false) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(option.badge) \(option.label)")
                            .font(.rdTitle)
                            .foregroundStyle(frequency == option ? RD.accent : RD.textPrimary)
                        // The battery cost is stated up front rather than discovered later.
                        Text(option.costHint)
                            .font(.rdLabelSmall)
                            .foregroundStyle(option == .ultra ? RD.warning : RD.textTertiary)
                    }
                    Spacer()
                    Text(option.descriptionText)
                        .font(.rdBodySmall)
                        .foregroundStyle(frequency == option ? RD.accent : RD.textSecondary)
                }
                .padding(RD.Space.lg)
            }
        }
        .buttonStyle(.plain)
    }

    private func field(
        _ label: String,
        text: Binding<String>,
        placeholder: String,
        error: String? = nil,
        keyboard: UIKeyboardType = .default
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.rdLabelSmall).foregroundStyle(RD.textSecondary)
            TextField(placeholder, text: text)
                .font(.rdBody)
                .foregroundStyle(RD.textPrimary)
                .keyboardType(keyboard)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(RD.Space.md)
                .background(RD.surface)
                .clipShape(RoundedRectangle(cornerRadius: RD.Radius.field, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: RD.Radius.field, style: .continuous)
                        .stroke(error == nil ? RD.outline : RD.danger, lineWidth: 1)
                }
            if let error {
                Text(error).font(.rdLabelSmall).foregroundStyle(RD.danger)
            }
        }
    }

    // MARK: - Behaviour

    /// Ultra is a single slot. Choosing it while another watch holds it raises a conflict the
    /// user resolves explicitly — RadarDeal never silently moves it or silently refuses.
    private func select(_ option: ScanFrequency) {
        guard option == .ultra else { frequency = option; return }
        if let holder = graph.store.ultraWatch(), holder.id != existing?.id {
            ultraConflict = holder
        } else {
            frequency = .ultra
        }
    }

    private func validate(_ value: String) {
        let trimmed = value.trimmed
        guard !trimmed.isEmpty else { urlError = nil; return }
        if case let .invalid(reason) = VintedQuery.checkSearchURL(trimmed) {
            urlError = reason
        } else {
            urlError = nil
        }
    }

    private func load() {
        guard let existing else {
            frequency = ScanFrequency.from(seconds: settings.defaultIntervalSeconds)
            return
        }
        mode = existing.isURLBased ? .url : .criteria
        name = existing.name
        keyword = existing.keyword ?? ""
        brand = existing.brand ?? ""
        maxPrice = existing.maxPrice.map { $0 == $0.rounded() ? String(Int($0)) : String($0) } ?? ""
        sourceURL = existing.sourceURL ?? ""
        frequency = existing.frequency
    }

    private func save() {
        guard canSave else { return }
        let urlBased = mode == .url

        var watch = Watch(
            id: existing?.id ?? 0,
            name: effectiveName,
            keyword: urlBased ? nil : keyword.trimmed.nilIfEmpty,
            brand: urlBased ? nil : brand.trimmed.nilIfEmpty,
            maxPrice: urlBased ? nil : Double(maxPrice.replacingOccurrences(of: ",", with: ".")),
            sourceURL: urlBased ? sourceURL.trimmed.nilIfEmpty : nil,
            intervalSeconds: frequency == .ultra ? ScanFrequency.fast.seconds : frequency.seconds,
            isUltra: frequency == .ultra,
            isActive: existing?.isActive ?? true,
            notifyEnabled: existing?.notifyEnabled ?? true,
            createdAt: existing?.createdAt ?? Date(),
            lastScanAt: existing?.lastScanAt,
            lastScanStatus: existing?.lastScanStatus ?? .never,
            baselineDone: existing?.baselineDone ?? false
        )

        let id: Int64
        if let existing {
            id = existing.id
            watch.id = id
            graph.store.updateWatch(watch)
            // The collected ids describe the old query; drop the cache with them.
            if criteriaChanged(from: existing, to: watch) {
                graph.store.resetBaseline(id: id)
                coordinator.invalidate(id)
            }
        } else {
            id = graph.store.createWatch(watch)
        }

        // Granting Ultra revokes it from whoever held it.
        if frequency == .ultra {
            graph.store.grantUltra(id: id)
        } else if existing?.isUltra == true {
            graph.store.revokeUltra(id: id)
        }

        coordinator.markDueNow(id)
        // Creating a watch is a clear signal that the user wants the radar running.
        settings.monitoringRequested = true
        coordinator.start()

        onDone()
        dismiss()
    }

    private func criteriaChanged(from before: Watch, to after: Watch) -> Bool {
        before.keyword != after.keyword || before.brand != after.brand
            || before.maxPrice != after.maxPrice || before.sourceURL != after.sourceURL
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
