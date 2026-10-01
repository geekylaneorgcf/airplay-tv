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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.service.DacpClient
import io.github.besliky.airplaytv.service.ReceiverState
import io.github.besliky.airplaytv.service.RemoteControl
import java.util.Locale

/**
 * What is playing while a sender streams audio only: cover art, title, artist, album and a
 * progress bar, like a TV with built-in AirPlay. Opens on its own when audio starts and closes
 * when the session ends. Back closes the screen; the audio keeps playing.
 *
 * The remote controls the sender: play/pause (also OK), next/fast-forward (also right) and
 * previous/rewind (also left). To protect OLED panels the screen dims after a while without a
 * key press and moves a few pixels every minute.
 */
class NowPlayingActivity : Activity() {

    private lateinit var art: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var albumView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var elapsedView: TextView
    private lateinit var totalView: TextView
    private lateinit var previousButton: ImageView
    private lateinit var playPauseButton: ImageView
    private lateinit var nextButton: ImageView
    private lateinit var content: View

    private val handler = Handler(Looper.getMainLooper())
    private var latest = ReceiverState.Snapshot()
    private var dimmed = false
    private var shiftStep = 0
    private var lastTitle = ""
    private var lastVolumeAt = 0L
    private lateinit var volumeHud: VolumeHud
    private lateinit var volumeGroup: LinearLayout
    private lateinit var volumeCaption: TextView
    private var levelBeforeMute = 1f

    private val stateListener: (ReceiverState.Snapshot) -> Unit = { render(it) }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val dim = Runnable { setDimmed(true) }
    private val hideVolume = Runnable {
        volumeGroup.animate().alpha(0f).translationX(-dp(14).toFloat()).setDuration(260).start()
    }

    private val shift = object : Runnable {
        override fun run() {
            shiftStep = (shiftStep + 1) % SHIFTS.size
            val (x, y) = SHIFTS[shiftStep]
            content.animate().translationX(dp(x).toFloat()).translationY(dp(y).toFloat()).setDuration(1000).start()
            handler.postDelayed(this, SHIFT_INTERVAL_MS)
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
        lastVolumeAt = ReceiverState.current.volumeAtMs
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        ReceiverState.observe(stateListener)
        handler.post(tick)
        handler.postDelayed(shift, SHIFT_INTERVAL_MS)
        scheduleDim()
    }

    override fun onStop() {
        ReceiverState.remove(stateListener)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(shift)
        handler.removeCallbacks(dim)
        handler.removeCallbacks(hideVolume)
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
        root.addView(art, LinearLayout.LayoutParams(dp(400), dp(400)))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = label(44f, R.color.text_primary, bold = true, lines = 2)
        artistView = label(30f, R.color.text_primary, bold = false, lines = 1)
        albumView = label(22f, R.color.text_secondary, bold = false, lines = 1)
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
        elapsedView = label(18f, R.color.text_secondary, bold = false, lines = 1)
        totalView = label(18f, R.color.text_secondary, bold = false, lines = 1).apply { gravity = Gravity.END }
        times.addView(elapsedView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(totalView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(times, wrapWidth().apply { topMargin = dp(4) })

        root.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(56)
        })
        previousButton = transportIcon(R.drawable.ic_skip_previous)
        playPauseButton = transportIcon(R.drawable.ic_pause)
        nextButton = transportIcon(R.drawable.ic_skip_next)
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        transport.addView(previousButton, LinearLayout.LayoutParams(dp(64), dp(64)))
        transport.addView(playPauseButton, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            marginStart = dp(32)
            marginEnd = dp(32)
        })
        transport.addView(nextButton, LinearLayout.LayoutParams(dp(64), dp(64)))
        column.addView(transport, wrapWidth().apply { topMargin = dp(28) })

        content = root

        volumeHud = VolumeHud(this)
        volumeCaption = label(15f, R.color.text_secondary, bold = true, lines = 1).apply { gravity = Gravity.CENTER }
        volumeGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0f
        }
        volumeGroup.addView(volumeHud, LinearLayout.LayoutParams(dp(76), dp(272)))
        volumeGroup.addView(volumeCaption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(volumeGroup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(44)
        })
        return frame
    }

    private fun transportIcon(drawable: Int) = ImageView(this).apply {
        setImageResource(drawable)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    /** A short pulse acknowledges a remote key press before the sender has answered. */
    private fun pulse(view: View) {
        view.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
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
        playPauseButton.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (s.title != lastTitle) {
            lastTitle = s.title
            setDimmed(false)
        }
        if (s.volumeAtMs != lastVolumeAt) {
            lastVolumeAt = s.volumeAtMs
            if (s.volume >= 0f) showVolume(s.volume, s.volume <= 0f, R.string.volume_source_phone)
        }
        updateProgress()
    }

    private fun showVolume(level: Float, muted: Boolean, source: Int) {
        setDimmed(false)
        volumeCaption.setText(source)
        volumeHud.setLevel(level, muted)
        volumeGroup.animate().cancel()
        if (volumeGroup.alpha < 1f) volumeGroup.translationX = -dp(14).toFloat()
        volumeGroup.animate().alpha(1f).translationX(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).start()
        handler.removeCallbacks(hideVolume)
        handler.postDelayed(hideVolume, VOLUME_SHOWN_MS)
    }

    /** The remote's volume buttons set the receiver's own volume (the stick cannot change the TV's). */
    private fun changeOutputLevel(delta: Float) {
        val next = (Math.round((ReceiverState.current.outputLevel + delta) * LEVEL_STEPS) / LEVEL_STEPS.toFloat())
            .coerceIn(0f, 1f)
        setOutputLevel(next)
    }

    private fun toggleMute() {
        val current = ReceiverState.current.outputLevel
        if (current > 0f) {
            levelBeforeMute = current
            setOutputLevel(0f)
        } else {
            setOutputLevel(levelBeforeMute.coerceAtLeast(1f / LEVEL_STEPS))
        }
    }

    private fun setOutputLevel(level: Float) {
        ReceiverState.update { it.copy(outputLevel = level) }
        showVolume(level, level <= 0f, R.string.volume_source_remote)
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
        if (s.audioActive && s.playing) position += SystemClock.elapsedRealtime() - s.positionAtMs
        position = position.coerceIn(0L, s.durationMs)
        progress.visibility = View.VISIBLE
        progress.progress = (position * PROGRESS_MAX / s.durationMs).toInt()
        elapsedView.text = formatTime(position)
        totalView.text = formatTime(s.durationMs)
    }

    // ---- remote control and OLED care ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { changeOutputLevel(1f / LEVEL_STEPS); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { changeOutputLevel(-1f / LEVEL_STEPS); return true }
            KeyEvent.KEYCODE_VOLUME_MUTE -> { if (event.repeatCount == 0) toggleMute(); return true }
        }
        if (dimmed) {
            setDimmed(false)
            if (keyCode != KeyEvent.KEYCODE_BACK) return true // the first press only wakes the screen
        }
        scheduleDim()
        val command = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> DacpClient.PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_RIGHT -> DacpClient.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_LEFT -> DacpClient.PREVIOUS
            else -> null
        } ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            RemoteControl.send(command)
            pulse(when (command) {
                DacpClient.NEXT -> nextButton
                DacpClient.PREVIOUS -> previousButton
                else -> playPauseButton
            })
        }
        return true
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dim)
        handler.postDelayed(dim, DIM_AFTER_MS)
    }

    private fun setDimmed(value: Boolean) {
        dimmed = value
        content.animate().alpha(if (value) DIM_ALPHA else 1f).setDuration(1500).start()
        if (!value) scheduleDim()
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    private companion object {
        const val PROGRESS_MAX = 1000
        const val PROGRESS_INTERVAL_MS = 500L
        const val DIM_AFTER_MS = 2 * 60 * 1000L
        const val VOLUME_SHOWN_MS = 2000L
        const val LEVEL_STEPS = 16
        const val DIM_ALPHA = 0.3f
        const val SHIFT_INTERVAL_MS = 60 * 1000L

        /** Offsets in dp the whole layout cycles through, so no pixel stays lit forever. */
        val SHIFTS = listOf(0 to 0, 14 to 0, 14 to 10, 0 to 10, -14 to 10, -14 to 0, -14 to -10, 0 to -10, 14 to -10)
    }
}
