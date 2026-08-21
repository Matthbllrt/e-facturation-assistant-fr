package app.mosaic.privatevault.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * The Mosaic mark: four offset squares.
 *
 * Abstract and geometric on purpose — no speech bubble, no padlock, no
 * Instagram cue. Someone glancing at the locked screen sees a shape, not a
 * category of app.
 */
@Composable
fun MosaicMark(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier = modifier) {
        val unit = size.minDimension / 5f
        val tiles = listOf(
            Triple(0f, 0f, scheme.onBackground),
            Triple(2.4f, 0.6f, scheme.onBackground.copy(alpha = 0.45f)),
            Triple(0.6f, 2.4f, scheme.onBackground.copy(alpha = 0.7f)),
            Triple(2.8f, 2.8f, scheme.onBackground.copy(alpha = 0.25f)),
        )
        tiles.forEach { (x, y, color) ->
            drawRect(
                color = color,
                topLeft = Offset(x * unit, y * unit),
                size = Size(unit * 1.9f, unit * 1.9f),
            )
        }
    }
}
