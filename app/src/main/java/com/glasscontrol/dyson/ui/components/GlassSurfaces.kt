package com.glasscontrol.dyson.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glasscontrol.dyson.ui.theme.LocalGlassColors

/**
 * A frosted panel.
 *
 * Real backdrop blur is not available to a widget, so the app deliberately uses
 * the same recipe — layered translucency, a hairline border and a crown
 * highlight — to keep both surfaces looking like one product.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    strong: Boolean = false,
    contentPadding: Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    val glass = LocalGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .background(if (strong) glass.surfaceStrong else glass.surface, shape)
            .border(BorderStroke(1.dp, glass.border), shape)
            .padding(contentPadding),
    ) {
        // A soft top highlight sells the "pane of glass" reading more than a
        // heavier border would.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(glass.highlight, shape)
        )
        content()
    }
}

/** A circular control that reacts instantly to touch, before any network call. */
@Composable
fun GlassControlButton(
    icon: Painter,
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    accent: Color? = null,
    size: Dp = 62.dp,
    onClick: () -> Unit,
) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "press")

    val tint = accent ?: glass.accent
    val background = when {
        !enabled -> glass.surface.copy(alpha = 0.4f)
        active -> tint.copy(alpha = if (glass.dark) 0.24f else 0.16f)
        else -> glass.surface
    }
    val borderColor = when {
        active -> tint.copy(alpha = 0.55f)
        else -> glass.border
    }
    val contentColor = when {
        !enabled -> glass.onSurfaceMuted.copy(alpha = 0.5f)
        active -> tint
        else -> glass.onSurface
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .scale(scale)
                .size(size)
                .background(background, RoundedCornerShape(percent = 50))
                .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(percent = 50))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(size * 0.38f),
            )
        }
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = if (active) tint else glass.onSurfaceMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A labelled reading, used across Home and Device. */
@Composable
fun ReadoutRow(label: String, value: String, accent: Boolean = false) {
    val glass = LocalGlassColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = glass.onSurfaceMuted,
        )
        Text(
            text = value,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = if (accent) glass.accent else glass.onSurface,
        )
    }
}

/** Ambient light behind the device render, tinted by what the machine is doing. */
@Composable
fun AmbientGlow(color: Color, intensity: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                0f to color.copy(alpha = 0.30f * intensity),
                0.6f to color.copy(alpha = 0.08f * intensity),
                1f to Color.Transparent,
            )
        )
    )
}
