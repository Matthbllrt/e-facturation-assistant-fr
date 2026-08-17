package com.glasscontrol.dyson.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.glasscontrol.dyson.domain.model.DysonState
import com.glasscontrol.dyson.ui.art.DysonArtwork
import com.glasscontrol.dyson.ui.art.DysonVisual
import com.glasscontrol.dyson.ui.theme.LocalGlassColors

/**
 * The machine, animated.
 *
 * This is the one place a continuous animation is acceptable: it only runs while
 * a control screen is on screen. The widget uses the same artwork rendered once
 * to a bitmap, so the two never drift apart visually.
 *
 * Motion is tied to the real machine: the body sways only when oscillation is
 * actually on, and the airflow arcs only move when the fan is running.
 */
@Composable
fun AnimatedDyson(
    state: DysonState,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    val transition = rememberInfiniteTransition(label = "dyson")

    // A full sweep takes about seven seconds, matching the machine's own pace.
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )

    val airflow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "airflow",
    )

    // Speed changes ease in rather than snapping, so the glow feels physical.
    val speedFraction by animateFloatAsState(
        targetValue = when {
            !state.power -> 0f
            state.autoMode -> 0.7f
            state.fanSpeed != null -> state.fanSpeed / 10f
            else -> 0.5f
        },
        animationSpec = tween(durationMillis = 450),
        label = "speed",
    )

    val oscillationPhase = if (state.oscillation && state.power) (sweep * 2f - 1f) else 0f

    Canvas(modifier = modifier) {
        drawIntoCanvas { canvas ->
            DysonArtwork.draw(
                canvas = canvas.nativeCanvas,
                width = size.width,
                height = size.height,
                visual = DysonVisual(
                    on = state.power,
                    speedFraction = speedFraction,
                    heating = state.heating,
                    nightMode = state.nightMode,
                    oscillating = state.oscillation,
                    dark = glass.dark,
                    oscillationPhase = oscillationPhase,
                    airflowPhase = if (state.power) airflow else 0f,
                ),
            )
        }
    }
}
