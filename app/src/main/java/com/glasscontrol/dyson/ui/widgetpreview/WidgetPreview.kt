package com.glasscontrol.dyson.ui.widgetpreview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasscontrol.dyson.R
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetTheme
import com.glasscontrol.dyson.domain.model.DysonState
import com.glasscontrol.dyson.ui.components.AnimatedDyson
import com.glasscontrol.dyson.widget.WidgetStatus
import com.glasscontrol.dyson.widget.WidgetUiState
import com.glasscontrol.dyson.widget.design.GlassTokens

/**
 * An in-app replica of the home-screen widget.
 *
 * Glance compositions cannot be hosted inside the app, so the configuration
 * screen redraws the same design in Compose. It reads the same [WidgetUiState],
 * so what is shown here is what the widget will show.
 */
@Composable
fun WidgetPreview(
    ui: WidgetUiState,
    state: DysonState,
    large: Boolean,
    systemDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = when (ui.config.theme) {
        WidgetTheme.DARK -> true
        WidgetTheme.LIGHT -> false
        WidgetTheme.AUTO -> systemDark
    }

    val surface = if (dark) {
        Brush.verticalGradient(
            0f to Color(0x6B44505F),
            0.5f to Color(0x8C1B2430),
            1f to Color(0xA60C1118),
        )
    } else {
        Brush.verticalGradient(
            0f to Color(0xD9FFFFFF),
            0.5f to Color(0xCCEEF3F8),
            1f to Color(0xBFD8E2EC),
        )
    }
    val primary = if (dark) GlassTokens.TextPrimary else GlassTokens.TextPrimaryLight
    val secondary = if (dark) GlassTokens.TextSecondary else GlassTokens.TextSecondaryLight
    val accent = if (dark) GlassTokens.AccentCyan else GlassTokens.AccentCyanLight
    val offline = if (dark) GlassTokens.StatusOffline else GlassTokens.StatusOfflineLight

    val dysonWidth = if (large) 90.dp else 56.dp
    val dysonHeight = if (large) 148.dp else 92.dp
    val controlHeight = if (large) 46.dp else 34.dp
    val gap = if (large) 10.dp else 6.dp
    val shape = RoundedCornerShape(GlassTokens.RadiusLarge)

    Box(
        modifier = modifier
            .background(surface, shape)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = if (dark) 0.20f else 0.35f)), shape)
            .padding(
                horizontal = GlassTokens.SurfacePaddingH,
                vertical = GlassTokens.SurfacePaddingV,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedDyson(state = state, modifier = Modifier.size(dysonWidth, dysonHeight))
            Spacer(Modifier.width(if (large) 14.dp else 10.dp))

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "D Y S O N",
                            color = primary,
                            fontSize = if (large) 14.sp else 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ui.model.isNotEmpty()) {
                                Text(
                                    text = ui.model,
                                    color = secondary,
                                    fontSize = if (large) 12.sp else 10.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "  •  ",
                                    color = GlassTokens.StatusUnknown,
                                    fontSize = if (large) 12.sp else 10.sp,
                                )
                            }
                            Text(
                                text = ui.statusLabel,
                                color = when {
                                    ui.isOnline -> accent
                                    ui.status == WidgetStatus.OFFLINE ||
                                        ui.status == WidgetStatus.ERROR -> offline
                                    else -> GlassTokens.StatusUnknown
                                },
                                fontSize = if (large) 12.sp else 10.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        tint = secondary,
                        modifier = Modifier.size(if (large) 18.dp else 15.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                if (ui.capabilities.fanSpeed) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PreviewStep(R.drawable.ic_minus, dark, primary, large)
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                text = ui.speedLabel,
                                color = primary,
                                fontSize = if (large) 34.sp else 24.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        PreviewStep(R.drawable.ic_plus, dark, primary, large)
                    }
                    Spacer(Modifier.height(gap))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ui.capabilities.power) {
                        PreviewControl(
                            modifier = Modifier.weight(1f),
                            iconRes = R.drawable.ic_power,
                            active = ui.power,
                            dark = dark, accent = accent, primary = primary,
                            height = controlHeight, large = large,
                        )
                    }
                    if (ui.capabilities.autoMode) {
                        PreviewControl(
                            modifier = Modifier.weight(1f),
                            label = "Auto",
                            active = ui.autoMode,
                            dark = dark, accent = accent, primary = primary,
                            height = controlHeight, large = large,
                        )
                    }
                    if (ui.capabilities.oscillation) {
                        PreviewControl(
                            modifier = Modifier.weight(1f),
                            iconRes = R.drawable.ic_oscillation,
                            active = ui.oscillation,
                            dark = dark, accent = accent, primary = primary,
                            height = controlHeight, large = large,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewStep(iconRes: Int, dark: Boolean, tint: Color, large: Boolean) {
    val shape = RoundedCornerShape(GlassTokens.RadiusSmall)
    Box(
        modifier = Modifier
            .width(if (large) 36.dp else 30.dp)
            .height(if (large) 48.dp else 34.dp)
            .background(Color.White.copy(alpha = if (dark) 0.12f else 0.24f), shape)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = if (dark) 0.15f else 0.30f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (large) 19.dp else 16.dp),
        )
    }
}

@Composable
private fun PreviewControl(
    modifier: Modifier,
    active: Boolean,
    dark: Boolean,
    accent: Color,
    primary: Color,
    height: Dp,
    large: Boolean,
    iconRes: Int? = null,
    label: String? = null,
) {
    val shape = RoundedCornerShape(GlassTokens.RadiusPill)
    val fill = when {
        active -> accent.copy(alpha = 0.22f)
        dark -> Color.White.copy(alpha = 0.12f)
        else -> Color(0xFF1B2733).copy(alpha = 0.24f)
    }
    val stroke = if (active) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f)

    Box(
        modifier = modifier
            .height(height)
            .background(fill, shape)
            .border(BorderStroke(1.dp, stroke), shape),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (active) accent else primary
        if (label != null) {
            Text(
                text = label,
                color = tint,
                fontSize = if (large) 14.sp else 12.sp,
                fontWeight = FontWeight.Medium,
            )
        } else if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(if (large) 21.dp else 17.dp),
            )
        }
    }
}
