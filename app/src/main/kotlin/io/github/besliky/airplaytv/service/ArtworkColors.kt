package io.github.besliky.airplaytv.service

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Colours taken from album art: a vivid [accent] for bars and highlights, and two dark shades for the
 * backdrop. The shades stay dark (value 0.07 to 0.22) so the screen is easy on an OLED panel.
 */
class ArtworkColors(val accent: Int, val backdropTop: Int, val backdropBottom: Int) {

    companion object {
        /** Used when there is no artwork, or when it is too dark or too grey to have a colour. */
        val NEUTRAL = ArtworkColors(0xFFA6A6AC.toInt(), 0xFF17171A.toInt(), 0xFF0B0B0D.toInt())

        private const val SAMPLE = 40
        private const val HUE_BUCKETS = 24

        fun from(bitmap: Bitmap): ArtworkColors {
            if (bitmap.width <= 0 || bitmap.height <= 0) return NEUTRAL
            val small = try {
                Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
            } catch (e: RuntimeException) {
                return NEUTRAL
            }
            val pixels = IntArray(SAMPLE * SAMPLE)
            small.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
            if (small !== bitmap) small.recycle()

            val weight = FloatArray(HUE_BUCKETS)
            val hueSum = FloatArray(HUE_BUCKETS)
            val satSum = FloatArray(HUE_BUCKETS)
            val valSum = FloatArray(HUE_BUCKETS)
            val hsv = FloatArray(3)
            var total = 0f
            for (pixel in pixels) {
                if (Color.alpha(pixel) < 200) continue
                Color.colorToHSV(pixel, hsv)
                val saturation = hsv[1]
                val value = hsv[2]
                if (value < 0.18f || saturation < 0.18f) continue // near black or near grey: no colour to take
                val w = saturation * (0.4f + 0.6f * value)
                val bucket = ((hsv[0] / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
                weight[bucket] += w
                hueSum[bucket] += hsv[0] * w
                satSum[bucket] += saturation * w
                valSum[bucket] += value * w
                total += w
            }
            if (total < MIN_TOTAL_WEIGHT) return NEUTRAL

            var best = 0
            for (i in 1 until HUE_BUCKETS) if (weight[i] > weight[best]) best = i
            val hue = hueSum[best] / weight[best]
            val sat = satSum[best] / weight[best]
            val value = valSum[best] / weight[best]

            val accent = Color.HSVToColor(floatArrayOf(hue, sat.coerceIn(0.35f, 0.85f), value.coerceIn(0.75f, 1f)))
            val top = Color.HSVToColor(floatArrayOf(hue, (sat * 0.75f).coerceIn(0.25f, 0.6f), 0.22f))
            val bottom = Color.HSVToColor(floatArrayOf(hue, (sat * 0.6f).coerceIn(0.2f, 0.5f), 0.07f))
            return ArtworkColors(accent, top, bottom)
        }

        /** Below this much coloured area (out of 1600 pixels) the artwork counts as colourless. */
        private const val MIN_TOTAL_WEIGHT = 12f
    }
}
