package com.radardeal.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radardeal.app.BuildConfig
import com.radardeal.app.monitoring.PerfSnapshot
import com.radardeal.app.monitoring.RadarPerf
import com.radardeal.app.ui.components.RdCard
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors

/**
 * "Ultra Radar Diagnostics" — the engine's own instrumentation, rendered.
 *
 * Compiled into debug builds only. The whole composable is behind a
 * [BuildConfig.VERBOSE_LOGGING] check, which is a compile-time constant, so R8 removes it
 * entirely from the release APK a buyer receives.
 */
@Composable
fun UltraDiagnosticsPanel(modifier: Modifier = Modifier) {
    if (!BuildConfig.VERBOSE_LOGGING) return

    val spacing = LocalSpacing.current
    val snapshot by RadarPerf.snapshot.collectAsStateWithLifecycle()

    RdCard(
        modifier = modifier.fillMaxWidth(),
        color = RadarColors.SurfaceElevated,
        border = null,
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = "⚡ ULTRA RADAR DIAGNOSTICS",
                style = MaterialTheme.typography.labelMedium,
                color = RadarColors.Accent,
            )
            Text(
                text = "Build debug uniquement — absent de l'APK de release.",
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.TextTertiary,
                modifier = Modifier.padding(bottom = spacing.sm),
            )

            snapshot.rows().forEach { (label, value) ->
                DiagnosticRow(label, value)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = RadarColors.TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = RadarColors.TextPrimary,
        )
    }
}

private fun PerfSnapshot.rows(): List<Pair<String, String>> = listOf(
    "Current interval" to ms(currentIntervalMs),
    "Last scan duration" to ms(lastScanDurationMs),
    "Average scan duration" to ms(averageScanDurationMs),
    "Last detection latency" to ms(lastDetectionLatencyMs),
    "Median detection latency" to ms(medianDetectionLatencyMs),
    "Cards scanned" to cardsScanned.toString(),
    "New cards" to newCards.toString(),
    "DOM mutations" to domMutations.toString(),
    "Live DOM detections" to liveDomDetections.toString(),
    "Poll detections" to pollDetections.toString(),
    "Scans / hour" to scansLastHour.toString(),
    "WebView" to if (webViewActive) "attached" else "idle",
    "Last error" to (lastError ?: "—"),
)

private fun ms(value: Long): String = if (value < 0) "—" else "$value ms"
