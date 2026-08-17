import SwiftUI
import WebKit

/// "Connexion Vinted" — the real Vinted website, in a normal WKWebView.
///
/// The user signs in on Vinted's own page. RadarDeal never reads, stores or transmits the
/// credentials: it only benefits from the cookies WebKit keeps afterwards, on this device,
/// exactly as Safari would. Those cookies never leave the phone.
///
/// The same screen is where a Vinted verification is cleared: whatever check Vinted shows, the
/// user completes it here themselves. RadarDeal never attempts to bypass one.
struct VintedLoginScreen: View {
    let session: VintedSession
    let onDone: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var isLoading = true

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if isLoading { ProgressView().tint(RD.accent).padding(.vertical, 4) }

                VintedWebView(dataStore: session.dataStore, isLoading: $isLoading)

                Text("RadarDeal ne voit jamais ton mot de passe. Les cookies de session restent "
                     + "sur cet iPhone et ne sont envoyés qu'à Vinted.")
                    .font(.rdLabelSmall)
                    .foregroundStyle(RD.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(RD.Space.lg)
                    .background(RD.surface)
            }
            .background(RD.background)
            .navigationTitle("Connexion Vinted")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Fermer") {
                        onDone()
                        dismiss()
                    }
                }
            }
        }
    }
}

private struct VintedWebView: UIViewRepresentable {
    let dataStore: WKWebsiteDataStore
    @Binding var isLoading: Bool

    func makeCoordinator() -> Coordinator { Coordinator(isLoading: $isLoading) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // The same store the fetcher reads, so a sign-in is immediately usable by the engine.
        configuration.websiteDataStore = dataStore

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.backgroundColor = UIColor(RD.background)
        webView.isOpaque = false
        webView.load(URLRequest(url: URL(string: "https://www.vinted.fr/")!))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        @Binding var isLoading: Bool

        init(isLoading: Binding<Bool>) { _isLoading = isLoading }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            isLoading = true
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            isLoading = false
        }

        func webView(
            _ webView: WKWebView,
            didFail navigation: WKNavigation!,
            withError error: Error
        ) {
            isLoading = false
        }
    }
}
