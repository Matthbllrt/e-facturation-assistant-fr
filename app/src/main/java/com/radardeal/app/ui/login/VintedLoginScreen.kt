package com.radardeal.app.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radardeal.app.RadarDealApp
import com.radardeal.app.core.RdLog
import com.radardeal.app.data.prefs.AppSettings
import com.radardeal.app.ui.components.EmptyState
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors

/**
 * "Connexion Vinted" — the real Vinted website, in a normal WebView.
 *
 * The user signs in on Vinted's own page. RadarDeal never reads, stores or transmits the
 * credentials: it only benefits from the cookies Android's WebView keeps afterwards, on this
 * device, exactly as a browser would. Those cookies never leave the phone.
 *
 * The same WebView is also the place to clear a Vinted verification: whatever check Vinted
 * shows, the user completes it here themselves. RadarDeal never attempts to bypass one.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VintedLoginScreen(onDone: () -> Unit) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val graph = remember { RadarDealApp.graph(context) }
    // The marketplace to open (vinted.fr by default). Read reactively so the screen does not
    // block on DataStore before showing anything.
    val appSettings by graph.settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
    val host = appSettings?.vintedHost ?: AppSettings.DEFAULT_HOST

    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Persist the cookie jar when leaving, so the fetchers see the new session immediately.
    DisposableEffect(Unit) {
        onDispose {
            runCatching { graph.session.flushCookies() }
            runCatching { webViewRef?.destroy() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RadarColors.Background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Fermer",
                    tint = RadarColors.TextPrimary,
                )
            }
            Column(modifier = Modifier.padding(start = spacing.xs)) {
                Text(
                    text = "Connexion Vinted",
                    style = MaterialTheme.typography.titleLarge,
                    color = RadarColors.TextPrimary,
                )
                Text(
                    text = "Connecte-toi sur la page officielle de Vinted",
                    style = MaterialTheme.typography.labelSmall,
                    color = RadarColors.TextSecondary,
                )
            }
        }

        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = RadarColors.Accent,
                trackColor = RadarColors.Surface,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (failed) {
                EmptyState(
                    icon = Icons.Rounded.Close,
                    title = "WebView indisponible",
                    message = "Ce téléphone n'a pas de composant WebView utilisable. " +
                        "Mets à jour « Android System WebView » ou Chrome depuis le Play Store, " +
                        "puis réessaie.",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        try {
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                setBackgroundColor(0xFF070B10.toInt())

                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        loading = true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                        runCatching { CookieManager.getInstance().flush() }
                                    }

                                    /**
                                     * Keep the user inside Vinted. Sign-in with a third-party
                                     * identity provider is intentionally allowed through, since
                                     * Vinted itself hosts those flows.
                                     */
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean = false
                                }

                                loadUrl("https://$host/")
                                webViewRef = this
                            }
                        } catch (t: Throwable) {
                            RdLog.e("Login", "WebView creation failed", t)
                            failed = true
                            // AndroidView needs a View: fall back to an empty one.
                            android.view.View(ctx)
                        }
                    },
                )
            }
        }

        Text(
            text = "RadarDeal ne voit jamais ton mot de passe. Les cookies de session restent " +
                "sur ce téléphone et ne sont envoyés qu'à Vinted.",
            style = MaterialTheme.typography.labelSmall,
            color = RadarColors.TextTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .background(RadarColors.Surface)
                .padding(spacing.lg)
                .navigationBarsPadding(),
        )
    }
}
