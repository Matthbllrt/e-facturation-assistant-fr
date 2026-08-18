package com.glasscontrol.dyson.ui.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.min

/**
 * What the machine should look like right now.
 *
 * @param speedFraction 0..1, drives how bright the amplifier glows.
 * @param oscillationPhase -1..1, shifts the device sideways for the in-app animation.
 * @param airflowPhase 0..1, animates the airflow arcs; ignored when static.
 */
data class DysonVisual(
    val on: Boolean = false,
    val speedFraction: Float = 0.5f,
    val heating: Boolean = false,
    val nightMode: Boolean = false,
    val oscillating: Boolean = false,
    val dark: Boolean = true,
    val oscillationPhase: Float = 0f,
    val airflowPhase: Float = 0f,
    /** Drawn dimmed, so an unreachable machine reads as inactive at a glance. */
    val offline: Boolean = false,
)

/**
 * Draws the Dyson tower purifier.
 *
 * One implementation serves both surfaces: the widget rasterises it to a bitmap
 * (Glance cannot run a Canvas composable) and the app draws it straight onto the
 * Compose canvas, so the machine looks identical in both places.
 *
 * Everything is proportional to the given box and soft edges come from gradient
 * shaders rather than blur mask filters, which keeps it safe to draw on a
 * hardware-accelerated canvas.
 */
object DysonArtwork {

    private val ACCENT_COOL = intArrayOf(104, 214, 244)
    private val ACCENT_WARM = intArrayOf(255, 146, 64)

    // Body gradients: [highlight, upper mid, core shadow, deep shadow, rim light, base shadow]
    private val RING_DARK = intArrayOf(0xFFF0F7FC.toInt(), 0xFFC4D1DC.toInt(), 0xFF768592.toInt(), 0xFF54616D.toInt(), 0xFF9EACB8.toInt(), 0xFF343E48.toInt())
    private val RING_LIGHT = intArrayOf(0xFFFFFFFF.toInt(), 0xFFEBF2F8.toInt(), 0xFFB7C4D0.toInt(), 0xFF9CAAB7.toInt(), 0xFFDBE5EE.toInt(), 0xFF7A8894.toInt())
    private val BASE_DARK = intArrayOf(0xFFE4EDF4.toInt(), 0xFFB2BFCB.toInt(), 0xFF687682.toInt(), 0xFF48545F.toInt(), 0xFF8C9AA6.toInt(), 0xFF2A333C.toInt())
    private val BASE_LIGHT = intArrayOf(0xFFFDFEFF.toInt(), 0xFFE4ECF3.toInt(), 0xFFAEBCC8.toInt(), 0xFF92A0AD.toInt(), 0xFFD2DDE7.toInt(), 0xFF707E8A.toInt())

    private val GRADIENT_STOPS = floatArrayOf(0f, 0.22f, 0.46f, 0.62f, 0.82f, 1f)

    /** How far an offline machine is faded back. */
    private const val OFFLINE_ALPHA = 130

    /** Renders to a fresh bitmap, for the widget. */
    fun renderBitmap(widthPx: Int, heightPx: Int, visual: DysonVisual): Bitmap {
        val bitmap = Bitmap.createBitmap(
            widthPx.coerceAtLeast(1),
            heightPx.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        draw(Canvas(bitmap), widthPx.toFloat(), heightPx.toFloat(), visual)
        return bitmap
    }

    fun draw(canvas: Canvas, width: Float, height: Float, visual: DysonVisual) {
        if (width <= 0f || height <= 0f) return

        // An unreachable machine is drawn faded rather than restyled: same shape,
        // visibly not live.
        val layer = if (visual.offline) {
            canvas.saveLayerAlpha(0f, 0f, width, height, OFFLINE_ALPHA)
        } else {
            -1
        }
        drawDevice(canvas, width, height, visual)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawDevice(canvas: Canvas, width: Float, height: Float, visual: DysonVisual) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Proportions follow a TP07: a 220 x 475 mm loop above a cylinder of
        // almost the same width, the whole machine 1054 mm tall.
        val deviceW = min(width * 0.56f, height * 0.285f)
        val centerX = width / 2f + visual.oscillationPhase * deviceW * 0.06f
        val ringTop = height * 0.035f
        val ringHeight = deviceW * 1.90f
        val ringBottom = ringTop + ringHeight
        val thickness = deviceW * 0.165f
        val innerW = deviceW - 2f * thickness
        val innerH = ringHeight - 2f * thickness * 1.02f
        val innerTop = ringTop + thickness * 1.02f

        val baseTop = ringBottom - deviceW * 0.17f
        val baseBottom = height * 0.945f
        val baseTopW = deviceW * 0.78f
        val baseBottomW = deviceW * 0.86f

        val accent = if (visual.heating) ACCENT_WARM else ACCENT_COOL
        val dim = if (visual.nightMode) 0.35f else 1f
        val intensity = (0.3f + 0.7f * visual.speedFraction.coerceIn(0f, 1f)) * dim

        val outerRect = RectF(centerX - deviceW / 2f, ringTop, centerX + deviceW / 2f, ringBottom)
        val innerRect = RectF(centerX - innerW / 2f, innerTop, centerX + innerW / 2f, innerTop + innerH)

        if (visual.on) drawGlow(canvas, paint, innerRect, accent, intensity)
        if (visual.on) drawAirflow(canvas, paint, centerX, innerRect, deviceW, accent, intensity, visual.airflowPhase)

        drawRing(canvas, paint, outerRect, innerRect, visual.dark)
        if (visual.on) drawAmplifierLight(canvas, paint, innerRect, accent, intensity)

        drawBase(
            canvas, paint, centerX, baseTop, baseBottom, baseTopW, baseBottomW, visual.dark,
        )
        drawContactShadow(canvas, paint, centerX, baseBottom, deviceW, height)
        if (visual.on) {
            drawFloorGlow(canvas, paint, centerX, baseBottom, deviceW, height, accent, intensity)
        }
    }

    /** Soft light spilling out of the amplifier opening. */
    private fun drawGlow(canvas: Canvas, paint: Paint, inner: RectF, accent: IntArray, intensity: Float) {
        val radius = max(inner.width(), inner.height()) * 0.62f
        paint.reset()
        paint.isAntiAlias = true
        paint.shader = RadialGradient(
            inner.centerX(), inner.centerY(), radius,
            intArrayOf(
                argb((120 * intensity).toInt(), accent),
                argb((45 * intensity).toInt(), accent),
                argb(0, accent),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(inner.centerX(), inner.centerY(), radius, paint)
        paint.shader = null
    }

    /** The loop itself: an annulus with a brushed-metal gradient. */
    private fun drawRing(canvas: Canvas, paint: Paint, outer: RectF, inner: RectF, dark: Boolean) {
        val ringPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addOval(outer, Path.Direction.CW)
            addOval(inner, Path.Direction.CW)
        }

        val colors = if (dark) RING_DARK else RING_LIGHT
        paint.reset()
        paint.isAntiAlias = true
        paint.shader = LinearGradient(
            outer.left, outer.top, outer.right, outer.bottom * 0.55f,
            colors, GRADIENT_STOPS, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(ringPath, paint)
        paint.shader = null

        canvas.save()
        canvas.clipPath(ringPath)

        // Contact shadow along the inner edge, giving the loop thickness.
        paint.style = Paint.Style.STROKE
        val shadowSteps = 4
        for (step in 0 until shadowSteps) {
            val grow = inner.width() * 0.012f * step
            val alpha = (70 * (1f - step / shadowSteps.toFloat())).toInt()
            paint.strokeWidth = inner.width() * 0.045f
            paint.color = Color.argb(alpha, 8, 14, 20)
            canvas.drawOval(
                RectF(
                    inner.left - grow, inner.top - grow,
                    inner.right + grow, inner.bottom + grow,
                ),
                paint,
            )
        }
        paint.style = Paint.Style.FILL

        // Specular highlight down the left wall and a smaller one on the crown.
        drawSoftBlob(
            canvas, paint,
            cx = outer.left + outer.width() * 0.10f,
            cy = outer.top + outer.height() * 0.42f,
            rx = outer.width() * 0.09f,
            ry = outer.height() * 0.28f,
            color = Color.WHITE, alpha = 190,
        )
        drawSoftBlob(
            canvas, paint,
            cx = outer.centerX() - outer.width() * 0.06f,
            cy = outer.top + outer.height() * 0.045f,
            rx = outer.width() * 0.17f,
            ry = outer.height() * 0.035f,
            color = Color.WHITE, alpha = 150,
        )
        canvas.restore()
    }

    /** Accent light rimming the inside of the loop while running. */
    private fun drawAmplifierLight(
        canvas: Canvas, paint: Paint, inner: RectF, accent: IntArray, intensity: Float,
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        val steps = 3
        for (step in 0 until steps) {
            paint.strokeWidth = inner.width() * (0.03f + step * 0.025f)
            paint.color = argb(((140 - step * 40) * intensity).toInt(), accent)
            canvas.drawOval(inner, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** Concentric arcs suggesting projected air. */
    private fun drawAirflow(
        canvas: Canvas, paint: Paint, centerX: Float, inner: RectF, deviceW: Float,
        accent: IntArray, intensity: Float, phase: Float,
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        val centerY = inner.centerY()
        for (index in 0 until 3) {
            // Each arc drifts outwards then fades, so the motion reads as flow.
            val drift = ((phase + index * 0.33f) % 1f)
            val radius = deviceW * (0.50f + index * 0.14f + drift * 0.05f)
            val fade = if (phase == 0f) 1f else (1f - drift * 0.45f)
            val alpha = (120 * intensity * (1f - index * 0.30f) * fade).toInt()
            if (alpha <= 0) continue

            paint.color = argb(alpha, accent)
            paint.strokeWidth = deviceW * 0.022f
            val box = RectF(
                centerX - radius, centerY - radius * 1.25f,
                centerX + radius, centerY + radius * 1.25f,
            )
            canvas.drawArc(box, -46f, 92f, false, paint)
            canvas.drawArc(box, 134f, 92f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    /** The cylinder, drawn over the loop's feet so they disappear into it. */
    private fun drawBase(
        canvas: Canvas, paint: Paint, centerX: Float,
        top: Float, bottom: Float, topWidth: Float, bottomWidth: Float, dark: Boolean,
    ) {
        // A pale body needs far less darkening at the foot, or the shading reads
        // as a smudge rather than as a curved surface turning away from the light.
        val footShade = if (dark) 165 else 80
        val capHeight = topWidth * 0.16f
        val footHeight = bottomWidth * 0.15f

        val basePath = Path().apply {
            moveTo(centerX - topWidth / 2f, top)
            lineTo(centerX - bottomWidth / 2f, bottom)
            arcTo(
                RectF(
                    centerX - bottomWidth / 2f, bottom - footHeight,
                    centerX + bottomWidth / 2f, bottom + footHeight,
                ),
                180f, -180f, false,
            )
            lineTo(centerX + topWidth / 2f, top)
            arcTo(
                RectF(
                    centerX - topWidth / 2f, top - capHeight,
                    centerX + topWidth / 2f, top + capHeight,
                ),
                0f, -180f, false,
            )
            close()
        }

        val colors = if (dark) BASE_DARK else BASE_LIGHT
        paint.reset()
        paint.isAntiAlias = true
        paint.shader = LinearGradient(
            centerX - bottomWidth / 2f, top, centerX + bottomWidth / 2f, top,
            colors, GRADIENT_STOPS, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(basePath, paint)
        paint.shader = null

        canvas.save()
        canvas.clipPath(basePath)

        // Shadow the loop casts as it enters the cylinder.
        drawSoftBlob(
            canvas, paint,
            cx = centerX, cy = top + capHeight * 0.4f,
            rx = topWidth * 0.55f, ry = capHeight * 1.5f,
            color = Color.rgb(10, 16, 22), alpha = 120,
        )

        // Narrow specular band running the height of the cylinder.
        paint.shader = LinearGradient(
            centerX - topWidth * 0.34f, 0f, centerX - topWidth * 0.08f, 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(105, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(centerX - topWidth * 0.4f, top, centerX, bottom + footHeight, paint)
        paint.shader = null

        // Darken towards the floor so the cylinder reads as a volume.
        val shadeTop = bottom - (bottom - top) * (if (dark) 0.30f else 0.20f)
        paint.shader = LinearGradient(
            0f, shadeTop, 0f, bottom + footHeight,
            intArrayOf(Color.argb(0, 9, 14, 19), Color.argb(footShade, 9, 14, 19)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(
            centerX - bottomWidth, shadeTop,
            centerX + bottomWidth, bottom + footHeight, paint,
        )
        paint.shader = null

        // Seam where the control collar sits.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, topWidth * 0.008f)
        paint.color = Color.argb(70, 20, 28, 36)
        val seamY = top + (bottom - top) * 0.26f
        canvas.drawLine(centerX - topWidth * 0.45f, seamY, centerX + topWidth * 0.45f, seamY, paint)
        paint.style = Paint.Style.FILL

        canvas.restore()
    }

    /** Diffuse light spilling onto the surface the machine stands on. */
    private fun drawFloorGlow(
        canvas: Canvas, paint: Paint, centerX: Float, baseBottom: Float,
        deviceW: Float, height: Float, accent: IntArray, intensity: Float,
    ) {
        val rx = deviceW * 0.95f
        val ry = height * 0.035f
        paint.reset()
        paint.isAntiAlias = true
        canvas.save()
        canvas.translate(centerX, baseBottom + height * 0.004f)
        canvas.scale(1f, ry / rx)
        paint.shader = RadialGradient(
            0f, 0f, rx,
            intArrayOf(
                argb((70 * intensity).toInt(), accent),
                argb((22 * intensity).toInt(), accent),
                argb(0, accent),
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, rx, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun drawContactShadow(
        canvas: Canvas, paint: Paint, centerX: Float, baseBottom: Float, deviceW: Float, height: Float,
    ) {
        val rx = deviceW * 0.62f
        val ry = height * 0.026f
        paint.reset()
        paint.isAntiAlias = true
        canvas.save()
        canvas.translate(centerX, baseBottom + height * 0.012f)
        canvas.scale(1f, ry / rx)
        paint.shader = RadialGradient(
            0f, 0f, rx,
            intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(50, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, rx, paint)
        paint.shader = null
        canvas.restore()
    }

    /**
     * A soft-edged ellipse.
     *
     * Radial gradients stand in for a blur here so the artwork can also be drawn
     * on a hardware-accelerated canvas, where blur mask filters are ignored.
     */
    private fun drawSoftBlob(
        canvas: Canvas, paint: Paint,
        cx: Float, cy: Float, rx: Float, ry: Float, color: Int, alpha: Int,
    ) {
        if (rx <= 0f || ry <= 0f) return
        paint.reset()
        paint.isAntiAlias = true
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(1f, ry / rx)
        paint.shader = RadialGradient(
            0f, 0f, rx,
            intArrayOf(
                Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb((alpha * 0.45f).toInt(), Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)),
            ),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, rx, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun argb(alpha: Int, rgb: IntArray): Int =
        Color.argb(alpha.coerceIn(0, 255), rgb[0], rgb[1], rgb[2])

    private fun max(a: Float, b: Float) = if (a > b) a else b
}
