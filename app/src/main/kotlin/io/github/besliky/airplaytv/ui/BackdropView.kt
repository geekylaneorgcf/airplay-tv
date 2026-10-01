package io.github.besliky.airplaytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Now Playing backdrop: a diagonal gradient between two colours. While the colours move (a new
 * cover) the plain gradient is drawn, which the GPU can animate for free. Once they hold still, a
 * dithered copy is made off the main thread and faded in on top, because a plain 8-bit gradient shows
 * straight one-level bands on a large OLED and the dithered one does not, see [Dither].
 */
class BackdropView(context: Context) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0, 0))
    private val paint = Paint()

    private var top = 0
    private var bottom = 0
    private var dithered: Bitmap? = null
    private var fade = 0f
    private var fader: ValueAnimator? = null
    private var generation = 0

    private val settle = Runnable { startDither() }

    init {
        @Suppress("DEPRECATION")
        gradient.setDither(true) // where the GPU honours it the animated gradient is smooth too
    }

    fun setColors(top: Int, bottom: Int) {
        if (top == this.top && bottom == this.bottom) return
        this.top = top
        this.bottom = bottom
        gradient.colors = intArrayOf(top, bottom)
        // Moving colours: show the plain gradient at once and make a new dithered copy when they stop.
        generation++
        fader?.cancel()
        fade = 0f
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        generation++
        fader?.cancel()
        fade = 0f
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(settle)
        fader?.cancel()
        generation++
        dithered = null // no recycle: a frame still in flight may draw it, and the GC frees the pixels
        super.onDetachedFromWindow()
    }

    private fun startDither() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || top == bottom) return // nothing to band: black, or one flat colour
        val token = generation
        val from = top
        val to = bottom
        worker.execute {
            val pixels = IntArray(w * h)
            Dither.gradient(pixels, w, h, from, to)
            val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            bitmap.setHasAlpha(false) // every pixel is opaque: the GPU can overwrite instead of blend
            handler.post {
                if (token != generation || !isAttachedToWindow) return@post
                dithered = bitmap
                fader?.cancel()
                fader = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = FADE_MS
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        fade = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = dithered?.takeIf { it.width == width && it.height == height }
        if (bitmap != null && fade >= 1f) {
            // Settled: the dithered picture is opaque and covers the view, so the gradient under it is not
            // drawn. One full-screen pass less on every frame the player redraws.
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            return
        }
        gradient.setBounds(0, 0, width, height)
        gradient.draw(canvas)
        if (bitmap != null && fade > 0f) {
            paint.alpha = (fade * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }
    }

    private companion object {
        const val SETTLE_MS = 300L
        const val FADE_MS = 350L

        val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "backdrop").apply { isDaemon = true }
        }
    }
}
