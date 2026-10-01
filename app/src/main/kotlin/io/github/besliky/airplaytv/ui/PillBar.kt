package io.github.besliky.airplaytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A rounded bar like the iPhone's progress and volume sliders: a dark track with a lighter fill and
 * round ends. The fill follows [setFraction] smoothly, and the bar swells while it is "active"
 * (being changed), the way the iOS slider does under a finger.
 */
class PillBar(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3E.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA6A6AC.toInt() }
    private val bar = RectF()
    private val clip = Path()

    private var shown = 0f
    private var fractionAnimator: ValueAnimator? = null
    private var thicknessPx = REST_DP * density
    private var thicknessAnimator: ValueAnimator? = null

    var trackColor: Int
        get() = trackPaint.color
        set(value) {
            trackPaint.color = value
            invalidate()
        }

    var fillColor: Int
        get() = fillPaint.color
        set(value) {
            fillPaint.color = value
            invalidate()
        }

    /** Moves the fill to [value] (0..1), gliding there when [animate] is set. */
    fun setFraction(value: Float, animate: Boolean) {
        val target = value.coerceIn(0f, 1f)
        fractionAnimator?.cancel()
        if (!animate || width == 0) {
            shown = target
            invalidate()
            return
        }
        fractionAnimator = ValueAnimator.ofFloat(shown, target).apply {
            duration = FILL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Swells the bar while it is being changed, and lets it settle again afterwards. */
    fun setActive(active: Boolean) {
        val target = (if (active) ACTIVE_DP else REST_DP) * density
        thicknessAnimator?.cancel()
        thicknessAnimator = ValueAnimator.ofFloat(thicknessPx, target).apply {
            duration = SWELL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                thicknessPx = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val thickness = thicknessPx.coerceAtMost(h)
        val top = (h - thickness) / 2f
        val radius = thickness / 2f
        bar.set(0f, top, w, top + thickness)
        canvas.drawRoundRect(bar, radius, radius, trackPaint)
        if (shown > 0f) {
            clip.reset()
            clip.addRoundRect(bar, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(0f, top, w * shown, top + thickness, fillPaint)
            canvas.restore()
        }
    }

    companion object {
        const val REST_DP = 8f
        const val ACTIVE_DP = 13f
        private const val FILL_MS = 160L
        private const val SWELL_MS = 170L
    }
}
