import SwiftUI

/// Three screens, shown once, on the very first launch.
struct OnboardingScreen: View {
    let onFinished: () -> Void

    @State private var page = 0

    private struct Page {
        let title: String
        let body: String
    }

    private let pages = [
        Page(title: "Ne rate plus une bonne annonce.",
             body: "RadarDeal surveille tes recherches pour toi."),
        Page(title: "Crée tes radars.",
             body: "Marque, modèle, prix ou recherche Vinted complète."),
        Page(title: "Sois alerté.",
             body: "Une nouvelle annonce apparaît ?\nRadarDeal te prévient."),
    ]

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                if page < pages.count - 1 {
                    Button("Passer", action: onFinished)
                        .font(.rdTitleSmall)
                        .foregroundStyle(RD.textSecondary)
                }
            }
            .frame(height: 44)
            .padding(.horizontal, RD.Space.screen)

            TabView(selection: $page) {
                ForEach(pages.indices, id: \.self) { index in
                    VStack(spacing: RD.Space.xl) {
                        RadarSweepMark().frame(width: 180, height: 180)
                        Text(pages[index].title)
                            .font(.rdDisplayMedium)
                            .foregroundStyle(RD.textPrimary)
                            .multilineTextAlignment(.center)
                        Text(pages[index].body)
                            .font(.rdBody)
                            .foregroundStyle(RD.textSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, RD.Space.xl)
                    .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))

            PrimaryButton(title: page == pages.count - 1 ? "Commencer" : "Suivant") {
                if page == pages.count - 1 {
                    onFinished()
                } else {
                    withAnimation { page += 1 }
                }
            }
            .padding(.horizontal, RD.Space.screen)
            .padding(.bottom, RD.Space.xl)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(RD.background)
    }
}

/// The animated radar used as the onboarding artwork — drawn, not an asset.
struct RadarSweepMark: View {
    @State private var angle: Double = 0

    var body: some View {
        ZStack {
            ForEach([1.0, 0.66, 0.33], id: \.self) { factor in
                Circle()
                    .stroke(RD.accent.opacity(0.18 + (1 - factor) * 0.3), lineWidth: 2)
                    .scaleEffect(factor)
            }
            Circle().fill(RD.accent).frame(width: 10, height: 10)

            AngularGradient(
                gradient: Gradient(colors: [.clear, RD.accent.opacity(0.42), .clear]),
                center: .center,
                angle: .degrees(angle)
            )
            .clipShape(Circle())
            .opacity(0.7)
        }
        .onAppear {
            withAnimation(.linear(duration: 3.6).repeatForever(autoreverses: false)) {
                angle = 360
            }
        }
    }
}
