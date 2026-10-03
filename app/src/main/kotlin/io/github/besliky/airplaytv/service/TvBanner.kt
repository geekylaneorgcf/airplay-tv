package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A short notice at the top of the screen over whatever app is in front, for a few seconds: what the stick just did to the TV (a scene,
 * a picture mode, a sleep timer). It takes no keys and no touches. Like the volume bar it is a window of the accessibility service, which
 * may draw over other apps without any permission of its own.
 */
class TvBanner(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var label: TextView? = null
    private val hide = Runnable { remove() }

    /** Shows [text] (in amber when [amber]) and takes it away after [forMs]; a notice that is up is replaced. */
    fun show(text: String, amber: Boolean = false, forMs: Long = SHOW_MS) {
        handler.removeCallbacks(hide)
        val view = label ?: makeLabel().also {
            if (!add(it)) return
            label = it
        }
        view.text = text
        view.setTextColor(if (amber) AMBER else Color.WHITE)
        handler.postDelayed(hide, forMs)
    }

    fun remove() {
        handler.removeCallbacks(hide)
        val view = label ?: return
        label = null
        try {
            windows().removeView(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot take the notice away", e)
        }
    }

    private fun windows() = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun makeLabel(): TextView {
        val density = service.resources.displayMetrics.density
        return TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((24 * density).toInt(), (10 * density).toInt(), (24 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xEB1C1C1E.toInt())
                cornerRadius = 40 * density
            }
        }
    }

    private fun add(view: TextView): Boolean {
        val density = service.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (TOP_DP * density).toInt()
        }
        return try {
            windows().addView(view, params)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot show a notice", e)
            false
        }
    }

    private companion object {
        const val TAG = "AirPlayTV-Notice"
        const val SHOW_MS = 3000L
        const val TOP_DP = 40
        val AMBER = 0xFFFFB340.toInt()
    }
}
