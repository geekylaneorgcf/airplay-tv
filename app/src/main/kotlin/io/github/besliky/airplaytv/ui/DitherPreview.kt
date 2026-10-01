package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Development aid: `adb shell am start -n <package>/.ui.MainActivity --es preview dither` fills the
 * screen with four strips of the same dark gradient, top to bottom: the plain framework gradient, the
 * framework gradient with its dither flag, a gradient dithered by [Dither], and the [BackdropView]
 * the Now Playing screen uses (give it a second to settle). Compare them in a screenshot with the
 * contrast turned up. Back closes it.
 */
object DitherPreview {

    private const val TOP = 0xFF372D27.toInt()
    private const val BOTTOM = 0xFF1B1613.toInt()

    fun show(activity: Activity) {
        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels
        val strip = metrics.heightPixels / 4

        fun flat(dither: Boolean) = View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(TOP, BOTTOM)).also {
                @Suppress("DEPRECATION")
                it.setDither(dither)
            }
        }

        val pixels = IntArray(width * strip)
        Dither.gradient(pixels, width, strip, TOP, BOTTOM)
        val software = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(Bitmap.createBitmap(pixels, width, strip, Bitmap.Config.ARGB_8888))
        }
        val real = BackdropView(activity).also { it.setColors(TOP, BOTTOM) }

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            for (view in listOf(flat(false), flat(true), software, real)) {
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, strip))
            }
        }
        Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(column)
            show()
        }
    }
}
