package com.radardeal.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.radardeal.app.domain.model.ScanStatus
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors

/**
 * The honest-error surface.
 *
 * Whenever Vinted declines to answer, RadarDeal shows exactly which of the five situations it
 * is in and what the user can do about it — never a generic "something went wrong". The copy
 * for each state lives on [ScanStatus] so the notification layer, the watch list and this
 * banner all say the same thing.
 */
@Composable
fun StatusBanner(
    status: ScanStatus,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    if (!status.isProblem) return

    val (accent, background, icon) = when (status) {
        ScanStatus.NEEDS_LOGIN ->
            Triple(RadarColors.Accent, RadarColors.AccentSoft, Icons.Rounded.Lock)
        ScanStatus.NEEDS_VERIFICATION ->
            Triple(RadarColors.Warning, RadarColors.WarningSoft, Icons.Rounded.Shield)
        ScanStatus.RATE_LIMITED ->
            Triple(RadarColors.Warning, RadarColors.WarningSoft, Icons.Rounded.HourglassEmpty)
        ScanStatus.NETWORK_ERROR ->
            Triple(RadarColors.Danger, RadarColors.DangerSoft, Icons.Rounded.CloudOff)
        else ->
            Triple(RadarColors.Danger, RadarColors.DangerSoft, Icons.Rounded.WarningAmber)
    }

    BannerSurface(
        title = status.label,
        message = status.explanation,
        accent = accent,
        background = background,
        icon = icon,
        modifier = modifier,
        action = action,
    )
}

@Composable
fun BannerSurface(
    title: String,
    message: String,
    accent: Color,
    background: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    RdCard(
        modifier = modifier.fillMaxWidth(),
        color = background,
        border = null,
    ) {
        Row(
            modifier = Modifier.padding(spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = RadarColors.TextPrimary,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RadarColors.TextSecondary,
                )
                action?.let {
                    Column(modifier = Modifier.padding(top = spacing.sm)) { it() }
                }
            }
        }
    }
}
