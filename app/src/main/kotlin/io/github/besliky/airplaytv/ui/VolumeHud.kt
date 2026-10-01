package io.github.besliky.airplaytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import io.github.besliky.airplaytv.R

/**
 * A vertical capsule that fills from the bottom, like the iPhone's volume indicator: a translucent
 * grey pill, a white fill for the level and a speaker icon at the bottom that switches colour
 * where the fill reaches it.
 */
class VolumeHud(context: Context) : View(context) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA0636366.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val capsule = RectF()
    private val clip = Path()
    private val iconOn = requireNotNull(context.getDrawable(R.drawable.ic_volume)).mutate()
    private val iconOff = requireNotNull(context.getDrawable(R.drawable.ic_volume_off)).mutate()

    private var shown = 0f
    private var target = 0f
    private var muted = false
    private var animator: ValueAnimator? = null

    /** Sets the level (0..1) and moves the fill there; [isMuted] swaps the icon. */
    fun setLevel(level: Float, isMuted: Boolean) {
        target = level.coerceIn(0f, 1f)
        muted = isMuted
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = FILL_MS
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = w / 2f

        capsule.set(0f, 0f, w, h)
        canvas.drawRoundRect(capsule, radius, radius, backgroundPaint)

        clip.reset()
        clip.addRoundRect(capsule, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(0f, h * (1f - shown), w, h, fillPaint)
        canvas.restore()

        val size = w * 0.5f
        val left = (w - size) / 2f
        val top = h - w * 0.3f - size
        val covered = h * (1f - shown) < top + size / 2f
        val icon = if (muted || target <= 0f) iconOff else iconOn
        icon.setTint(if (covered) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt())
        icon.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        icon.draw(canvas)
    }

    private companion object {
        const val FILL_MS = 140L
    }
}
