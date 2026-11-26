package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import io.github.besliky.airplaytv.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monitor mode, for a screen that takes no volume or power from the stick (no CEC, no IR, no network): what the stick can still
 * do by itself.
 *
 * Volume: the system moves its volume index on the remote's keys but leaves the HDMI mixer at full level, and a global effect is
 * ignored on this output, so each playing audio session gets a [DynamicsProcessing] gain that follows the index. Finding the
 * sessions takes `dumpsys media.audio_flinger`, which needs `android.permission.DUMP` (granted once from a computer).
 *
 * Brightness and warmth: layers drawn over everything (the stick cannot reach the monitor's own settings; on an OLED panel a
 * darker pixel does emit less light). Nothing is drawn at full brightness with warmth off. "Screen off" is the same layer, black.
 *
 * Made and used on the main thread; the dumps and the effects live on its own thread.
 */
class MonitorDisplay(private val service: AccessibilityService) {

    private val settings = Settings(service)
    private val audio = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("monitor-audio").apply { start() }
    private val worker = Handler(thread.looper)

    private val effects = HashMap<Int, DynamicsProcessing>() // worker only
    private var lastIndex = -1 // worker only

    @Volatile
    var muted = false
        private set

    /** The DUMP permission is missing: the volume cannot work until it is granted. */
    @Volatile
    var needsPermission = false
        private set

    var blanked = false
        private set

    /** While the panel is open it shows the values itself. */
    var barSuppressed = false

    /** Told on the main thread when the volume or mute changed. */
    var onChanged: (() -> Unit)? = null

    private var shade: Shade? = null
    private var bar: Bar? = null
    private val hideBar = Runnable { removeBar() }

    val volume: Int get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    private val maxVolume: Int get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private val tick = object : Runnable {
        override fun run() {
            update()
            worker.postDelayed(this, POLL_MS)
        }
    }

    private val rescan = Runnable { update() }

    /** A new stream plays at full level until it has an effect, so look at once instead of at the next tick. */
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            worker.removeCallbacks(rescan)
            worker.post(rescan)
            worker.postDelayed(rescan, 120)
            worker.postDelayed(rescan, 350)
        }
    }

    private val volumeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, AudioManager.STREAM_MUSIC) != AudioManager.STREAM_MUSIC) return
            muted = false
            worker.post(rescan)
            showVolume()
            onChanged?.invoke()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // the flag exists from API 33, and is given there
    fun start() {
        val filter = IntentFilter(VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(volumeChanged, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            service.registerReceiver(volumeChanged, filter)
        }
        audio.registerAudioPlaybackCallback(playback, worker)
        applyShade()
        worker.post(tick)
    }

    fun stop() {
        service.unregisterReceiver(volumeChanged)
        audio.unregisterAudioPlaybackCallback(playback)
        onChanged = null
        worker.removeCallbacksAndMessages(null)
        worker.post {
            releaseAll()
            thread.quitSafely()
        }
        removeBar()
        removeShade()
    }

    fun toggleMute() {
        muted = !muted
        worker.post(rescan)
        showVolume()
        onChanged?.invoke()
    }

    /** A volume key at the end of its range moves nothing, but it still ends a mute. */
    fun unmute() {
        if (muted) toggleMute()
    }

    fun stepVolume(step: Int) {
        muted = false
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (step > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
        worker.post(rescan)
    }

    var brightness: Int
        get() = settings.monitorBrightness
        set(value) {
            settings.monitorBrightness = value
            applyShade()
        }

    var warmth: Int
        get() = settings.monitorWarmth
        set(value) {
            settings.monitorWarmth = value
            applyShade()
        }

    /** Black over everything until a key is pressed; sound goes on. */
    fun blank(on: Boolean) {
        blanked = on
        applyShade()
    }

    // ---- audio (worker thread)

    private fun update() {
        try {
            val index = volume
            if (index != lastIndex) {
                event("volume index $lastIndex -> $index")
                lastIndex = index
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            val live = readSessions()
            needsPermission = live == null
            if (live == null) return
            for (session in effects.keys.toList()) if (session !in live) release(session)
            val db = MonitorScale.gainDb(index, maxVolume, muted)
            if (db >= 0f) {
                releaseAll()
                return
            }
            for (session in live) {
                val effect = effects[session]
                try {
                    if (effect != null) {
                        effect.setInputGainAllChannelsTo(db)
                    } else {
                        val made = DynamicsProcessing(0, session, null)
                        made.setInputGainAllChannelsTo(db)
                        made.setEnabled(true)
                        effects[session] = made
                        event("attached to session $session at $db dB")
                    }
                } catch (e: RuntimeException) {
                    event("session $session did not take the gain: $e")
                    release(session)
                }
            }
        } catch (e: Exception) {
            Log.w(SERVICE, "monitor volume update failed", e)
        }
    }

    private fun readSessions(): Set<Int>? {
        val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "media.audio_flinger"))
        return try {
            process.inputStream.bufferedReader().useLines { MonitorScale.sessions(it, android.os.Process.myPid()) }
        } finally {
            process.destroy()
        }
    }

    private fun release(session: Int) {
        val effect = effects.remove(session) ?: return
        try {
            effect.setEnabled(false)
            effect.release()
        } catch (_: RuntimeException) {
            // the session is gone already
        }
    }

    private fun releaseAll() {
        for (session in effects.keys.toList()) release(session)
    }

    /** The system log holds only seconds on this stick: keep our own, readable with `adb pull`. */
    private fun event(line: String) {
        Log.i(SERVICE, "monitor: $line")
        try {
            val file = File(service.getExternalFilesDir(null) ?: return, "monitor-events.log")
            val entry = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + " " + line + "\n"
            if (file.length() < LOG_LIMIT) file.appendText(entry) else file.writeText(entry)
        } catch (_: Exception) {
            // diagnostics only
        }
    }

    // ---- what is drawn (main thread)

    private fun showVolume() {
        if (barSuppressed) return
        val index = volume
        showBar(if (muted) "Mute" else "$index", if (muted) 0f else index / maxVolume.toFloat())
    }

    private fun showBar(label: String, level: Float) {
        main.removeCallbacks(hideBar)
        val view = bar ?: Bar(service).also {
            if (!OverlayWindows.add(service, it, BAR_WIDTH_DP, BAR_HEIGHT_DP, Gravity.END or Gravity.CENTER_VERTICAL, xDp = 28)) return
            bar = it
        }
        view.show(label, level)
        main.postDelayed(hideBar, SHOW_MS)
    }

    private fun removeBar() {
        main.removeCallbacks(hideBar)
        val view = bar ?: return
        bar = null
        OverlayWindows.remove(service, view)
    }

    private fun applyShade() {
        val dim = if (blanked) 255 else MonitorScale.dimAlpha(brightness)
        val warm = if (blanked) 0 else MonitorScale.warmAlpha(warmth)
        if (dim == 0 && warm == 0) {
            removeShade()
            return
        }
        val view = shade ?: Shade(service).also {
            if (!addShade(it)) return
            shade = it
        }
        view.set(dim, warm)
    }

    private fun addShade(view: View): Boolean {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        return try {
            (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, params)
            true
        } catch (e: RuntimeException) {
            Log.w(SERVICE, "cannot dim the screen", e)
            false
        }
    }

    private fun removeShade() {
        val view = shade ?: return
        shade = null
        OverlayWindows.remove(service, view)
    }

    private class Shade(context: Context) : View(context) {
        private var dim = 0
        private var warm = 0

        fun set(dim: Int, warm: Int) {
            this.dim = dim
            this.warm = warm
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (warm > 0) canvas.drawColor(Color.argb(warm, 255, 120, 0))
            if (dim > 0) canvas.drawColor(Color.argb(dim, 0, 0, 0))
        }
    }

    /** A tall rounded track on the right with a white fill rising from the bottom and the number above it. */
    private class Bar(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD0303033.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 15f * density
            setShadowLayer(4f * density, 0f, density, 0xE0000000.toInt())
        }
        private val box = RectF()
        private val clip = Path()
        private var label = ""
        private var level = 0f

        fun show(label: String, level: Float) {
            this.label = label
            this.level = level.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val top = 28f * density
            canvas.drawText(label, w / 2f, 18f * density, text)
            box.set(0f, top, w, h)
            val radius = w / 2f
            canvas.drawRoundRect(box, radius, radius, track)
            clip.reset()
            clip.addRoundRect(box, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(0f, h - (h - top) * level, w, h, fill)
            canvas.restore()
        }
    }

    private companion object {
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val POLL_MS = 1000L
        const val SHOW_MS = 1800L
        const val LOG_LIMIT = 200_000L
        const val BAR_WIDTH_DP = 48
        const val BAR_HEIGHT_DP = 290
    }
}
