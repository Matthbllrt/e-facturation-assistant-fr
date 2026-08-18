package com.glasscontrol.dyson.ui.art

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Keeps rendered machines around between widget updates.
 *
 * A widget repaints on every state change, and re-rasterising the artwork each
 * time would burn CPU for an identical image. States collapse to a handful of
 * distinct pictures, so a small cache serves nearly every repaint.
 */
object DysonRenderCache {

    private data class Key(
        val widthPx: Int,
        val heightPx: Int,
        val on: Boolean,
        /** Speed quantised: the glow is not visibly different between 6 and 7. */
        val intensityStep: Int,
        val heating: Boolean,
        val nightMode: Boolean,
        val dark: Boolean,
        val offline: Boolean,
    )

    private val cache = object : LruCache<Key, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
    }

    fun render(widthPx: Int, heightPx: Int, visual: DysonVisual): Bitmap {
        val width = widthPx.coerceIn(1, MAX_EDGE_PX)
        val height = heightPx.coerceIn(1, MAX_EDGE_PX)
        val step = (visual.speedFraction.coerceIn(0f, 1f) * INTENSITY_STEPS).toInt()

        val key = Key(
            widthPx = width,
            heightPx = height,
            on = visual.on,
            intensityStep = step,
            heating = visual.heating,
            nightMode = visual.nightMode,
            dark = visual.dark,
            offline = visual.offline,
        )

        cache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        val bitmap = DysonArtwork.renderBitmap(
            widthPx = width,
            heightPx = height,
            // Quantised so the cached image matches the key exactly.
            visual = visual.copy(
                speedFraction = step / INTENSITY_STEPS.toFloat(),
                oscillationPhase = 0f,
                airflowPhase = 0f,
            ),
        )
        cache.put(key, bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()

    /**
     * Upper bound on a single edge.
     *
     * A widget's whole RemoteViews payload has to cross an IPC boundary, and an
     * over-sampled bitmap is the quickest way to blow that budget.
     */
    const val MAX_EDGE_PX = 480

    private const val INTENSITY_STEPS = 4
    private const val MAX_BYTES = 6 * 1024 * 1024
}
