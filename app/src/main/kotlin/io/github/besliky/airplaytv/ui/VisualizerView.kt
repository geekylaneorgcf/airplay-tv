package io.github.besliky.airplaytv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.besliky.airplaytv.service.AudioTap
import io.github.besliky.airplaytv.service.Spectrum

/**
 * A calm visualizer for the bottom edge of the Now Playing screen: a row of thin bars that rise and fall with the music, at a
 * quarter of full brightness and in the colour of the cover. Always moving, so no pixel of it stays lit, and quiet enough to
 * read the controls over it. It reads the last moments of the music from [AudioTap] and does its arithmetic with [Spectrum].
 */
class VisualizerView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val levels = FloatArray(BANDS)
    private val target = FloatArray(BANDS)
    private val samples = FloatArray(WINDOW)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    /** The colour of the bars, before they are dimmed. */
    var color: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            paint.color = (value and 0x00FFFFFF) or (BAR_ALPHA shl 24)
            invalidate()
        }

    init {
        paint.color = (color and 0x00FFFFFF) or (BAR_ALPHA shl 24)
    }

    /** Reads the music and moves the bars one step towards it; false when there is nothing to show (the music is silent or has not begun). */
    fun step(): Boolean {
        val have = AudioTap.tap.snapshot(samples)
        if (have) Spectrum.bars(samples, SAMPLE_RATE, target) else target.fill(0f)
        var lit = false
        for (i in 0 until BANDS) {
            // quick to rise, slow to fall, as a meter does
            levels[i] += (target[i] - levels[i]) * if (target[i] > levels[i]) RISE else FALL
            if (levels[i] > 0.02f) lit = true
        }
        invalidate()
        return lit
    }

    /** Lets the bars sink to nothing. */
    fun rest() {
        var lit = false
        for (i in 0 until BANDS) {
            levels[i] *= 0.8f
            if (levels[i] > 0.01f) lit = true
        }
        if (lit) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val slot = w / BANDS
        val barWidth = slot * 0.46f
        val minimum = 3f * density
        for (i in 0 until BANDS) {
            val height = (minimum + (h - minimum) * levels[i]).coerceAtMost(h)
            val left = i * slot + (slot - barWidth) / 2f
            bar.set(left, h - height, left + barWidth, h)
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    private companion object {
        const val BANDS = 36
        const val WINDOW = 1024
        const val SAMPLE_RATE = 44100
        const val BAR_ALPHA = 0x42
        const val RISE = 0.55f
        const val FALL = 0.14f
    }
}
