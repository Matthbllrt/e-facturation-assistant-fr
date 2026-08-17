import SwiftUI
import RadarDealCore

/// Preferences, Vinted session management, history reset and the legal notice.
struct SettingsScreen: View {
    @Environment(AppGraph.self) private var graph
    @Environment(SettingsStore.self) private var settings

    @State private var showLogin = false
    @State private var hasSession = false
    @State private var confirmClear = false
    @State private var confirmSignOut = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: RD.Space.md) {
                    Text("Réglages")
                        .font(.rdDisplayMedium)
                        .foregroundStyle(RD.textPrimary)
                        .padding(.top, RD.Space.xl)

                    SectionHeader(title: "Notifications")
                    RDCard {
                        VStack(spacing: 0) {
                            toggleRow(
                                "Notifications",
                                "Alerter à chaque nouvelle annonce et baisse de prix",
                                isOn: Binding(
                                    get: { settings.notificationsEnabled },
                                    set: { settings.notificationsEnabled = $0 }
                                )
                            )
                            toggleRow(
                                "Son", "Jouer un son à la réception d'une alerte",
                                isOn: Binding(
                                    get: { settings.soundEnabled },
                                    set: { settings.soundEnabled = $0 }
                                ),
                                enabled: settings.notificationsEnabled
                            )
                        }
                    }

                    SectionHeader(title: "Fréquence par défaut")
                    HStack(spacing: RD.Space.sm) {
                        ForEach(ScanFrequency.allCases, id: \.self) { option in
                            RDFilterChip(
                                label: option.label,
                                selected: settings.defaultIntervalSeconds == option.seconds
                            ) {
                                settings.defaultIntervalSeconds = option.seconds
                            }
                        }
                    }
                    Text("Appliquée aux nouvelles veilles. Les veilles existantes gardent la leur.")
                        .font(.rdLabelSmall)
                        .foregroundStyle(RD.textTertiary)

                    SectionHeader(title: "Ouvrir les annonces dans")
                    HStack(spacing: RD.Space.sm) {
                        ForEach(OpenTarget.allCases, id: \.self) { option in
                            RDFilterChip(label: option.label,
                                         selected: settings.openTarget == option) {
                                settings.openTarget = option
                            }
                        }
                    }

                    SectionHeader(title: "Session Vinted")
                    RDCard {
                        VStack(alignment: .leading, spacing: RD.Space.md) {
                            Text(hasSession ? "Session Vinted active" : "Aucune session Vinted")
                                .font(.rdTitle)
                                .foregroundStyle(hasSession ? RD.accent : RD.textPrimary)
                            Text("RadarDeal utilise ta session Vinted normale, exactement comme "
                                 + "un navigateur. Tu te connectes toi-même sur la page "
                                 + "officielle de Vinted : l'application ne voit jamais ton mot "
                                 + "de passe, et les cookies restent sur cet iPhone.")
                                .font(.rdBodySmall)
                                .foregroundStyle(RD.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                            HStack(spacing: RD.Space.sm) {
                                SecondaryButton(
                                    title: hasSession ? "Rouvrir Vinted" : "Se connecter",
                                    systemImage: "person.crop.circle", tint: RD.accent
                                ) { showLogin = true }
                                if hasSession {
                                    SecondaryButton(title: "Déconnecter",
                                                    systemImage: "rectangle.portrait.and.arrow.right",
                                                    tint: RD.danger) {
                                        confirmSignOut = true
                                    }
                                }
                            }
                        }
                        .padding(RD.Space.lg)
                    }

                    SectionHeader(title: "Données")
                    Button { confirmClear = true } label: {
                        RDCard {
                            HStack(spacing: RD.Space.md) {
                                Image(systemName: "trash").foregroundStyle(RD.danger)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Effacer l'historique")
                                        .font(.rdTitle).foregroundStyle(RD.textPrimary)
                                    Text("Supprime les annonces collectées et l'historique des "
                                         + "prix. Les veilles et les favoris sont conservés.")
                                        .font(.rdBodySmall)
                                        .foregroundStyle(RD.textSecondary)
                                        .multilineTextAlignment(.leading)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.right").foregroundStyle(RD.textTertiary)
                            }
                            .padding(RD.Space.lg)
                        }
                    }
                    .buttonStyle(.plain)

                    SectionHeader(title: "Ce qu'iOS autorise")
                    RDCard(color: RD.surfaceElevated, bordered: false) {
                        Text("Contrairement à Android, iOS ne permet à aucune application "
                             + "tierce de surveiller en continu en arrière-plan. RadarDeal "
                             + "scanne à pleine vitesse tant qu'il est ouvert au premier plan ; "
                             + "une fois l'application fermée ou en arrière-plan, iOS n'accorde "
                             + "qu'un rafraîchissement occasionnel, quand il le décide "
                             + "(typiquement plusieurs minutes à plusieurs heures).\n\n"
                             + "Garde RadarDeal ouvert pour la surveillance rapide.")
                            .font(.rdBodySmall)
                            .foregroundStyle(RD.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(RD.Space.lg)
                    }

                    SectionHeader(title: "À propos")
                    RDCard {
                        VStack(alignment: .leading, spacing: RD.Space.sm) {
                            Text("RadarDeal \(appVersion)")
                                .font(.rdTitle).foregroundStyle(RD.textPrimary)
                            Text("Moniteur d'annonces Vinted. Toutes tes données — veilles, "
                                 + "favoris, historique, prix et session — restent sur cet "
                                 + "iPhone : RadarDeal n'a aucun serveur.")
                                .font(.rdBodySmall)
                                .foregroundStyle(RD.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                            Text("RadarDeal n'est ni affilié ni approuvé par Vinted.")
                                .font(.rdLabelSmall)
                                .foregroundStyle(RD.textTertiary)
                        }
                        .padding(RD.Space.lg)
                    }
                }
                .padding(.horizontal, RD.Space.screen)
                .padding(.bottom, RD.Space.xxl)
            }
            .background(RD.background)
            .scrollContentBackground(.hidden)
            .sheet(isPresented: $showLogin) {
                VintedLoginScreen(session: graph.session) {
                    Task { hasSession = await graph.session.hasSession(host: settings.vintedHost) }
                }
            }
            .alert("Effacer l'historique ?", isPresented: $confirmClear) {
                Button("Annuler", role: .cancel) {}
                Button("Effacer", role: .destructive) {
                    graph.store.clearHistoryKeepingFavorites()
                    graph.knownIDs.clear()
                    graph.notifier.cancelAll()
                }
            } message: {
                Text("Les annonces collectées et l'historique des prix seront supprimés. Tes "
                     + "veilles et tes favoris sont conservés.")
            }
            .alert("Déconnecter Vinted ?", isPresented: $confirmSignOut) {
                Button("Annuler", role: .cancel) {}
                Button("Déconnecter", role: .destructive) {
                    Task {
                        await graph.session.signOut()
                        hasSession = false
                    }
                }
            } message: {
                Text("Les cookies de session Vinted seront supprimés de cet iPhone. Tu devras "
                     + "te reconnecter pour que la surveillance reprenne.")
            }
        }
        .task { hasSession = await graph.session.hasSession(host: settings.vintedHost) }
    }

    private func toggleRow(
        _ title: String, _ subtitle: String,
        isOn: Binding<Bool>, enabled: Bool = true
    ) -> some View {
        HStack(spacing: RD.Space.md) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.rdTitle)
                    .foregroundStyle(enabled ? RD.textPrimary : RD.textTertiary)
                Text(subtitle).font(.rdBodySmall).foregroundStyle(RD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Toggle("", isOn: isOn).labelsHidden().tint(RD.accent).disabled(!enabled)
        }
        .padding(RD.Space.lg)
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }
}
