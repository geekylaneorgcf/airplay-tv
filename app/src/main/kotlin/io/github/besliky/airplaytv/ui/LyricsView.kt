package io.github.besliky.airplaytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.animation.DecelerateInterpolator
import io.github.besliky.airplaytv.service.Lyrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Scrolling lyrics. With time-stamped lyrics the current line is large and bright, its neighbours
 * shrink and fade, and the list glides to keep the current line in view. Plain lyrics scroll slowly
 * with the track's progress instead. Text is laid out once per size change, not per frame.
 */
class LyricsView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private var lyrics: Lyrics? = null
    private var layouts: List<StaticLayout> = emptyList()
    private var tops = FloatArray(0)
    private var contentHeight = 0f

    private var index = -1
    private var focus = 0f
    private var scroll = 0f
    private var animator: ValueAnimator? = null

    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((56 * density).toInt())
    }

    override fun getTopFadingEdgeStrength(): Float = 1f

    override fun getBottomFadingEdgeStrength(): Float = 1f

    fun setLyrics(value: Lyrics?) {
        lyrics = value
        index = -1
        focus = -1f
        scroll = 0f
        animator?.cancel()
        rebuild()
        invalidate()
    }

    /** Synced lyrics: moves the highlight to the line sung at [positionMs]. */
    fun setPosition(positionMs: Long) {
        val l = lyrics ?: return
        if (!l.synced || layouts.isEmpty()) return
        val next = l.indexAt(positionMs)
        if (next != index) {
            index = next
            glideTo(next)
        }
    }

    /** Plain lyrics: scrolls through the text as the track plays. */
    fun setProgress(fraction: Float) {
        val l = lyrics ?: return
        if (l.synced || layouts.isEmpty()) return
        scroll = fraction.coerceIn(0f, 1f) * max(0f, contentHeight - height * 0.55f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
        animator?.cancel()
        focus = index.toFloat()
        if (lyrics?.synced == true) scroll = scrollFor(index)
    }

    private fun rebuild() {
        val l = lyrics
        val width = width - paddingLeft - paddingRight
        if (l == null || width <= 0 || l.lines.isEmpty()) {
            layouts = emptyList()
            tops = FloatArray(0)
            contentHeight = 0f
            return
        }
        val gap = 20f * density
        val built = ArrayList<StaticLayout>(l.lines.size)
        val offsets = FloatArray(l.lines.size)
        var y = 0f
        for ((i, line) in l.lines.withIndex()) {
            val text = if (line.text.isBlank()) " " else line.text
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.06f)
                .build()
            built += layout
            offsets[i] = y
            y += layout.height + gap
        }
        layouts = built
        tops = offsets
        contentHeight = y - gap
    }

    /** The scroll offset that puts line [i] at about 40% of the height. */
    private fun scrollFor(i: Int): Float {
        if (i < 0 || i >= layouts.size) return -height * 0.25f
        return tops[i] + layouts[i].height / 2f - height * 0.4f
    }

    private fun glideTo(i: Int) {
        val toFocus = i.toFloat()
        val toScroll = scrollFor(i)
        animator?.cancel()
        if (abs(toFocus - focus) > JUMP_LINES) {
            focus = toFocus
            scroll = toScroll
            invalidate()
            return
        }
        val fromFocus = focus
        val fromScroll = scroll
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GLIDE_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val p = it.animatedValue as Float
                focus = fromFocus + (toFocus - fromFocus) * p
                scroll = fromScroll + (toScroll - fromScroll) * p
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val l = lyrics ?: return
        if (layouts.isEmpty()) return
        val viewHeight = height.toFloat()
        for (i in layouts.indices) {
            val layout = layouts[i]
            val top = tops[i] - scroll
            if (top + layout.height < -16f || top > viewHeight + 16f) continue

            val alpha: Float
            val scale: Float
            if (l.synced) {
                val d = abs(i - focus)
                alpha = if (d <= 1f) 1f - 0.58f * d else max(0.12f, 0.42f - 0.12f * (d - 1f))
                scale = 1f - 0.1f * min(d, 1f)
            } else {
                alpha = 0.85f
                scale = 1f
            }
            textPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), top)
            canvas.scale(scale, scale, 0f, layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private companion object {
        const val GLIDE_MS = 520L
        const val JUMP_LINES = 6f
    }
}
