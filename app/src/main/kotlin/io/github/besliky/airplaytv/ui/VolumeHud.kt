package io.github.besliky.airplaytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import io.github.besliky.airplaytv.R

/**
 * A vertical capsule that fills from the bottom, like the iPhone's volume indicator: a dark
 * translucent pill with a hairline edge and a soft shadow, a white fill for the level, and a
 * speaker icon at the bottom whose waves follow the level and whose colour flips where the fill
 * reaches it. The pill gives a small rubber-band bounce when the level hits either end.
 */
class VolumeHud(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2C2C30.toInt() }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x33FFFFFF
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val capsule = RectF()
    private val inner = RectF()
    private val clip = Path()

    private val iconOff = icon(R.drawable.ic_volume_off)
    private val iconMute = icon(R.drawable.ic_volume_mute)
    private val iconLow = icon(R.drawable.ic_volume_down)
    private val iconHigh = icon(R.drawable.ic_volume)

    private var shown = 0f
    private var target = 0f
    private var muted = false
    private var animator: ValueAnimator? = null

    init {
        elevation = 14f * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width / 2f)
            }
        }
    }

    private fun icon(res: Int) = requireNotNull(context.getDrawable(res)).mutate()

    /** Sets the level (0..1) and moves the fill there; [isMuted] shows the crossed-out speaker. */
    fun setLevel(level: Float, isMuted: Boolean) {
        val previous = target
        target = level.coerceIn(0f, 1f)
        muted = isMuted
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = FILL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        if ((target >= 1f && previous >= 1f) || (target <= 0f && previous <= 0f)) bounce()
        invalidate()
    }

    private fun bounce() {
        animate().cancel()
        animate().scaleX(1.06f).scaleY(1.06f).setDuration(80).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
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

        val half = edgePaint.strokeWidth / 2f
        inner.set(half, half, w - half, h - half)
        canvas.drawRoundRect(inner, radius - half, radius - half, edgePaint)

        val size = w * 0.46f
        val left = (w - size) / 2f
        val top = h - w * 0.34f - size
        val covered = h * (1f - shown) < top + size / 2f
        val icon = when {
            muted || target <= 0f -> iconOff
            target < 0.4f -> iconMute
            target < 0.75f -> iconLow
            else -> iconHigh
        }
        icon.setTint(if (covered) 0xFF2C2C30.toInt() else 0xFFFFFFFF.toInt())
        icon.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        icon.draw(canvas)
    }

    private companion object {
        const val FILL_MS = 130L
    }
}
