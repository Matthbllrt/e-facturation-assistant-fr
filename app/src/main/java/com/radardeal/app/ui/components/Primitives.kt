package com.radardeal.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radardeal.app.ui.theme.CardShape
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.PillShape
import com.radardeal.app.ui.theme.RadarColors

/** The standard elevated panel every screen is built from. */
@Composable
fun RdCard(
    modifier: Modifier = Modifier,
    color: Color = RadarColors.Surface,
    border: BorderStroke? = BorderStroke(1.dp, RadarColors.Outline),
    shape: androidx.compose.ui.graphics.Shape = CardShape,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        color = color,
        shape = shape,
        border = border,
        content = content,
    )
}

/** Small uppercase capsule: NOUVEAU, PRIX ↓, 🔥 EXCELLENT DEAL… */
@Composable
fun BadgePill(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = RadarColors.Accent,
    background: Color = RadarColors.AccentSoft,
) {
    Box(
        modifier = modifier
            .background(background, PillShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** One of the three big numbers on the dashboard. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = RadarColors.TextPrimary,
) {
    RdCard(modifier = modifier, color = RadarColors.SurfaceElevated) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.displayMedium,
                color = accent,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "DERNIÈRES OPPORTUNITÉS" — the small, wide-tracked section label. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = RadarColors.TextSecondary,
        )
        trailing?.invoke()
    }
}

/** Selectable filter capsule used by the Radar feed. */
@Composable
fun RdFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = if (selected) RadarColors.Accent else RadarColors.SurfaceElevated,
        animationSpec = tween(180),
        label = "chipBackground",
    )
    val content by animateColorAsState(
        targetValue = if (selected) Color(0xFF00251E) else RadarColors.TextSecondary,
        animationSpec = tween(180),
        label = "chipContent",
    )

    Box(
        modifier = modifier
            .background(background, PillShape)
            .then(
                if (selected) Modifier else Modifier.border(1.dp, RadarColors.Outline, PillShape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = content,
            maxLines = 1,
        )
    }
}

/** The pulsing dot that marks a live watch. */
@Composable
fun LiveDot(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    size: Dp = 8.dp,
) {
    if (!active) {
        Box(
            modifier = modifier
                .size(size)
                .background(RadarColors.TextTertiary, CircleShape)
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "liveDot")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDotAlpha",
    )

    Box(
        modifier = modifier
            .size(size)
            .alpha(pulse)
            .background(RadarColors.Live, CircleShape)
    )
}

/** Full-panel empty state: icon, title, one explanatory line, optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.xl, vertical = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(RadarColors.SurfaceElevated, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = RadarColors.Accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = RadarColors.TextPrimary,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = RadarColors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        action?.let {
            Box(modifier = Modifier.padding(top = spacing.sm)) { it() }
        }
    }
}
