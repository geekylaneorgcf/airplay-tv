package io.github.besliky.airplaytv.ui

import kotlin.math.floor

/**
 * Smooth gradients on an 8-bit display. A gradient from one dark colour to another changes by a single
 * level every few dozen pixels, and on a large OLED those one-level steps show as straight diagonal
 * bands. [gradient] decides per pixel whether to round a channel up or down using a fixed noise
 * pattern, so each step dissolves into fine grain instead of a hard edge.
 */
object Dither {

    /**
     * Fills [out] (row-major, [width] x [height], opaque ARGB) with the gradient that
     * `GradientDrawable.Orientation.TL_BR` draws: [top] at the top-left corner, [bottom] at the
     * bottom-right, constant along lines perpendicular to the diagonal.
     */
    fun gradient(out: IntArray, width: Int, height: Int, top: Int, bottom: Int) {
        require(width > 0 && height > 0 && out.size >= width * height)
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = 1f / (w * w + h * h)
        val r0 = (top shr 16 and 0xFF).toFloat()
        val g0 = (top shr 8 and 0xFF).toFloat()
        val b0 = (top and 0xFF).toFloat()
        val dr = (bottom shr 16 and 0xFF) - r0
        val dg = (bottom shr 8 and 0xFF) - g0
        val db = (bottom and 0xFF) - b0
        for (y in 0 until height) {
            val rowT = (y + 0.5f) * h * scale
            val base = y * width
            for (x in 0 until width) {
                val t = (x + 0.5f) * w * scale + rowT
                val n = noise(x, y)
                // The value is never negative, so truncating is the same as rounding down.
                val r = (r0 + dr * t + n).toInt().coerceIn(0, 255)
                val g = (g0 + dg * t + n).toInt().coerceIn(0, 255)
                val b = (b0 + db * t + n).toInt().coerceIn(0, 255)
                out[base + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** The exact (unrounded) channel value at a pixel, for comparing a result against. */
    fun exact(x: Int, y: Int, width: Int, height: Int, from: Int, to: Int): Float {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = ((x + 0.5f) * w + (y + 0.5f) * h) / (w * w + h * h)
        return from + (to - from) * t
    }

    /** Interleaved gradient noise: a cheap, even pattern in [0, 1) with no visible grid or streaks. */
    internal fun noise(x: Int, y: Int): Float {
        val f = 0.06711056f * x + 0.00583715f * y
        val g = 52.9829189f * (f - floor(f))
        return g - floor(g)
    }
}
