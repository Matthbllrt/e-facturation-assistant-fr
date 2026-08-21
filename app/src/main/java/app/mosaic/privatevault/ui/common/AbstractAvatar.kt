package app.mosaic.privatevault.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * A deterministic geometric tile used instead of the person's real photo.
 *
 * Generated from the alias, so it never encodes anything about the real
 * identity, and it is the same every launch without storing an image.
 */
@Composable
fun AbstractAvatar(
    seed: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.surfaceVariant,
        scheme.outline,
        scheme.primary.copy(alpha = 0.55f),
        scheme.onSurfaceVariant.copy(alpha = 0.35f),
    )

    Canvas(modifier = modifier.size(size)) {
        val random = Random(seed.absoluteValue.toLong())
        val cells = 3
        val cell = Size(this.size.width / cells, this.size.height / cells)
        for (row in 0 until cells) {
            for (column in 0 until cells) {
                val color: Color = palette[random.nextInt(palette.size)]
                drawRect(
                    color = color,
                    topLeft = Offset(column * cell.width, row * cell.height),
                    size = cell,
                )
            }
        }
    }
}
