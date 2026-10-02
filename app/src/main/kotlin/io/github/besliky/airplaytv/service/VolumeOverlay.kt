package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The volume, as a small bar at the bottom of the screen over whatever app is in front, for as long as the level was last
 * moved plus a moment. Fire OS shows nothing for the volume while the TV owns it, and with the remote's keys taken (see
 * [VolumeKeys]) it would not show them either, so this is where the owner sees the volume move, from the remote or from the
 * phone. It is a window of the accessibility service, which may draw over other apps without any permission of its own.
 */
class VolumeOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var bar: Bar? = null
    private val hide = Runnable { remove() }

    /** Shows the bar at [level] (0 to 1) and keeps it up for a moment from now. */
    fun show(level: Float) {
        handler.removeCallbacks(hide)
        val view = bar ?: Bar(service).also {
            if (!add(it)) return
            bar = it
        }
        view.level = level.coerceIn(0f, 1f)
        handler.postDelayed(hide, SHOW_MS)
    }

    fun remove() {
        handler.removeCallbacks(hide)
        val view = bar ?: return
        bar = null
        try {
            windows().removeView(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot take the volume bar away", e)
        }
    }

    private fun windows() = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun add(view: Bar): Boolean {
        val density = service.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (WIDTH_DP * density).toInt(),
            (HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (BOTTOM_DP * density).toInt()
        }
        return try {
            windows().addView(view, params)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot show the volume bar", e)
            false
        }
    }

    /** A pill: a speaker (with waves as loud as it is, a slash when it is silent) and a bar filled to the level. */
    private class Bar(context: Context) : View(context) {

        var level = 0f
            set(value) {
                field = value
                invalidate()
            }

        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = 0xEB1C1C1E.toInt()
            canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, paint)

            val icon = 26f * density
            val left = 24f * density
            canvas.save()
            canvas.translate(left, (h - icon) / 2f)
            paint.color = Color.WHITE
            path.reset()
            path.moveTo(0.04f * icon, 0.37f * icon)
            path.lineTo(0.28f * icon, 0.37f * icon)
            path.lineTo(0.55f * icon, 0.12f * icon)
            path.lineTo(0.55f * icon, 0.88f * icon)
            path.lineTo(0.28f * icon, 0.63f * icon)
            path.lineTo(0.04f * icon, 0.63f * icon)
            path.close()
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f * density
            paint.strokeCap = Paint.Cap.ROUND
            if (level <= 0f) {
                canvas.drawLine(0.12f * icon, 0.14f * icon, 0.94f * icon, 0.86f * icon, paint)
            } else {
                wave(canvas, 0.55f * icon, 0.5f * icon, 0.24f * icon)
                if (level > 0.5f) wave(canvas, 0.55f * icon, 0.5f * icon, 0.42f * icon)
            }
            canvas.restore()

            val trackLeft = left + icon + 18f * density
            val trackRight = w - 28f * density
            val centre = h / 2f
            val thickness = 8f * density
            paint.style = Paint.Style.FILL
            paint.color = 0x33FFFFFF
            canvas.drawRoundRect(trackLeft, centre - thickness / 2f, trackRight, centre + thickness / 2f, thickness / 2f, thickness / 2f, paint)
            if (level > 0f) {
                paint.color = Color.WHITE
                val fillRight = maxOf(trackLeft + (trackRight - trackLeft) * level, trackLeft + thickness)
                canvas.drawRoundRect(trackLeft, centre - thickness / 2f, fillRight, centre + thickness / 2f, thickness / 2f, thickness / 2f, paint)
            }
        }

        private fun wave(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            box.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(box, -42f, 84f, false, paint)
        }
    }

    private companion object {
        const val TAG = "AirPlayTV-Volume"
        const val SHOW_MS = 1800L
        const val WIDTH_DP = 360
        const val HEIGHT_DP = 60
        const val BOTTOM_DP = 56
    }
}
