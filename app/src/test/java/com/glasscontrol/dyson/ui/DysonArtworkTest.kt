package com.glasscontrol.dyson.ui

import android.graphics.Bitmap
import com.glasscontrol.dyson.ui.art.DysonArtwork
import com.glasscontrol.dyson.ui.art.DysonVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the widget artwork for real, so a blank machine on the home screen
 * would fail here rather than on the user's phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DysonArtworkTest {

    private fun render(visual: DysonVisual): Bitmap =
        DysonArtwork.renderBitmap(184, 276, visual)

    private fun Bitmap.opaquePixels(): Int {
        var count = 0
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (android.graphics.Color.alpha(getPixel(x, y)) > 24) count++
            }
        }
        return count
    }

    /**
     * Mean pixel of the amplifier opening, where the accent light shows.
     *
     * The bitmap is transparent there rather than composited on a background, so
     * brightness lives in the alpha channel while hue lives in RGB.
     */
    private data class Sample(val alpha: Int, val red: Int, val green: Int, val blue: Int)

    private fun Bitmap.centreSample(): Sample {
        var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in (width * 4 / 10) until (width * 6 / 10)) {
            for (y in (height * 15 / 100) until (height * 35 / 100)) {
                val pixel = getPixel(x, y)
                a += android.graphics.Color.alpha(pixel)
                r += android.graphics.Color.red(pixel)
                g += android.graphics.Color.green(pixel)
                b += android.graphics.Color.blue(pixel)
                n++
            }
        }
        return Sample((a / n).toInt(), (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    @Test
    fun `draws a machine rather than an empty bitmap`() {
        val bitmap = render(DysonVisual(on = false, dark = true))

        val painted = bitmap.opaquePixels()
        val total = bitmap.width * bitmap.height
        assertTrue("Artwork painted nothing at all", painted > total / 20)
        assertTrue("Artwork filled the whole canvas, so nothing reads as a device", painted < total)
    }

    @Test
    fun `fan speed changes how strongly the amplifier is lit`() {
        val low = render(DysonVisual(on = true, speedFraction = 0.1f, dark = true)).centreSample()
        val high = render(DysonVisual(on = true, speedFraction = 1f, dark = true)).centreSample()

        assertTrue("A faster fan should glow more", high.alpha > low.alpha)
    }

    @Test
    fun `a running machine is visibly brighter than a stopped one`() {
        val off = render(DysonVisual(on = false, dark = true)).opaquePixels()
        val on = render(DysonVisual(on = true, speedFraction = 1f, dark = true)).opaquePixels()

        // The glow and airflow arcs add lit pixels outside the body.
        assertTrue("Running state should light up more of the widget", on > off)
    }

    @Test
    fun `heating tints the amplifier warm and cooling tints it cold`() {
        val cool = render(
            DysonVisual(on = true, speedFraction = 1f, heating = false, dark = true)
        ).centreSample()
        val warm = render(
            DysonVisual(on = true, speedFraction = 1f, heating = true, dark = true)
        ).centreSample()

        assertTrue("Cool mode should read blue rather than red", cool.blue > cool.red)
        assertTrue("Heat mode should read red rather than blue", warm.red > warm.blue)
    }

    @Test
    fun `night mode dims the accent light`() {
        val normal = render(DysonVisual(on = true, speedFraction = 1f, dark = true)).centreSample()
        val night = render(
            DysonVisual(on = true, speedFraction = 1f, nightMode = true, dark = true)
        ).centreSample()

        // The opening is transparent, so the light's strength is its alpha.
        assertTrue(
            "Night mode must be dimmer than a fully lit machine",
            night.alpha < normal.alpha,
        )
        assertTrue("Night mode should still be lit", night.alpha > 0)
    }

    @Test
    fun `renders at widget and hero sizes without failing`() {
        val compact = DysonArtwork.renderBitmap(104, 172, DysonVisual(on = true))
        val hero = DysonArtwork.renderBitmap(276, 414, DysonVisual(on = true))

        assertEquals(104, compact.width)
        assertEquals(414, hero.height)
        assertTrue(compact.opaquePixels() > 0)
        assertTrue(hero.opaquePixels() > 0)
    }

    @Test
    fun `a degenerate size is ignored instead of crashing`() {
        // Glance can ask for a bitmap before the widget has a measured size.
        val bitmap = DysonArtwork.renderBitmap(0, 0, DysonVisual(on = true))
        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
    }
}
