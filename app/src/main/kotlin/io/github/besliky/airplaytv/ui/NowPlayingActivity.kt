package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.service.ReceiverState
import java.util.Locale

/**
 * What is playing while a sender streams audio only: cover art, title, artist, album and a
 * progress bar, like a TV with built-in AirPlay. Opens on its own when audio starts and closes
 * when the session ends. Back closes the screen; the audio keeps playing.
 */
class NowPlayingActivity : Activity() {

    private lateinit var art: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var albumView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var elapsedView: TextView
    private lateinit var totalView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var latest = ReceiverState.Snapshot()

    private val stateListener: (ReceiverState.Snapshot) -> Unit = { render(it) }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Same as the mirror screen: a sender can connect while the device sleeps.
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            )
        }
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        ReceiverState.observe(stateListener)
        handler.post(tick)
    }

    override fun onStop() {
        ReceiverState.remove(stateListener)
        handler.removeCallbacks(tick)
        super.onStop()
    }

    // ---- views ----

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(sizeSp: Float, colorRes: Int, bold: Boolean, lines: Int): TextView =
        TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(getColor(colorRes))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.background))
            setPadding(dp(64), dp(48), dp(64), dp(48))
        }

        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(getColor(R.color.surface))
        }
        root.addView(art, LinearLayout.LayoutParams(dp(360), dp(360)))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = label(34f, R.color.text_primary, bold = true, lines = 2)
        artistView = label(24f, R.color.text_primary, bold = false, lines = 1)
        albumView = label(18f, R.color.text_secondary, bold = false, lines = 1)
        column.addView(titleView, wrapWidth())
        column.addView(artistView, wrapWidth().apply { topMargin = dp(8) })
        column.addView(albumView, wrapWidth().apply { topMargin = dp(4) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(getColor(R.color.text_primary))
            progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.surface))
        }
        column.addView(progress, wrapWidth().apply { topMargin = dp(40) })

        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        elapsedView = label(16f, R.color.text_secondary, bold = false, lines = 1)
        totalView = label(16f, R.color.text_secondary, bold = false, lines = 1).apply { gravity = Gravity.END }
        times.addView(elapsedView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(totalView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(times, wrapWidth().apply { topMargin = dp(4) })

        root.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(56)
        })
        return root
    }

    private fun wrapWidth() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // ---- state ----

    private fun render(s: ReceiverState.Snapshot) {
        latest = s
        if (s.status != ReceiverState.Status.CONNECTED || s.videoActive) {
            finish()
            return
        }
        val sender = s.clientName ?: getString(R.string.now_playing_device)
        titleView.text = if (s.title.isNotBlank()) s.title else getString(R.string.now_playing_from, sender)
        artistView.text = s.artist
        artistView.visibility = if (s.artist.isBlank()) View.GONE else View.VISIBLE
        albumView.text = s.album
        albumView.visibility = if (s.album.isBlank()) View.GONE else View.VISIBLE
        art.setImageBitmap(s.artwork)
        updateProgress()
    }

    private fun updateProgress() {
        val s = latest
        if (s.durationMs <= 0 || s.positionMs < 0) {
            progress.visibility = View.INVISIBLE
            elapsedView.text = ""
            totalView.text = ""
            return
        }
        var position = s.positionMs
        if (s.audioActive) position += SystemClock.elapsedRealtime() - s.positionAtMs
        position = position.coerceIn(0L, s.durationMs)
        progress.visibility = View.VISIBLE
        progress.progress = (position * PROGRESS_MAX / s.durationMs).toInt()
        elapsedView.text = formatTime(position)
        totalView.text = formatTime(s.durationMs)
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    private companion object {
        const val PROGRESS_MAX = 1000
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
