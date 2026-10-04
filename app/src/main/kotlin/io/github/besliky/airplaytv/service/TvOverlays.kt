package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

/**
 * Cards that are drawn over whatever app is in front, as windows of the accessibility service (which may do that without a permission of its
 * own; see [VolumeOverlay]). None of them takes the focus or a touch; the ones that take keys get them from the key service
 * ([MenuKeyService]), and give them back when they close.
 */
internal object OverlayWindows {

    private const val TAG = "AirPlayTV-Overlay"

    /** Adds [view] to the screen with the size and place given in dp; false when the system refuses. */
    fun add(service: AccessibilityService, view: View, widthDp: Int, heightDp: Int, gravity: Int, xDp: Int = 0, yDp: Int = 0): Boolean {
        val density = service.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (widthDp * density).toInt(),
            (heightDp * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            x = (xDp * density).toInt()
            y = (yDp * density).toInt()
        }
        return try {
            (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, params)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot show an overlay", e)
            false
        }
    }

    fun remove(service: AccessibilityService, view: View) {
        try {
            (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot take an overlay away", e)
        }
    }
}

/** Shared pieces of the drawing: the dark rounded card and the text styles. */
internal class CardPaints(density: Float) {
    val d = density
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF01C1C1E.toInt() }
    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = 0x33FFFFFF
    }
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 17f * density
    }
    val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB0FFFFFF.toInt()
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = 14f * density
    }
    val box = RectF()

    fun card(canvas: Canvas, w: Float, h: Float, radiusDp: Float = 22f) {
        box.set(0f, 0f, w, h)
        canvas.drawRoundRect(box, radiusDp * d, radiusDp * d, fill)
        box.inset(rim.strokeWidth / 2f, rim.strokeWidth / 2f)
        canvas.drawRoundRect(box, radiusDp * d, radiusDp * d, rim)
    }

    fun fit(text: String, paint: Paint, room: Float): String =
        TextUtils.ellipsize(text, android.text.TextPaint(paint), room, TextUtils.TruncateAt.END).toString()
}

/**
 * A small card with the cover, the title and the artist, for about four seconds, when a new song starts while another app is in front.
 * With the mini-remote on, a press of OK while it is up opens that.
 */
class NowPlayingCard(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var view: CardView? = null
    private val hide = Runnable { remove() }

    val isShowing: Boolean get() = view != null

    fun show(title: String, artist: String, cover: Bitmap?, hintOk: Boolean, forMs: Long = SHOW_MS) {
        handler.removeCallbacks(hide)
        val card = view ?: CardView(service).also {
            if (!OverlayWindows.add(service, it, WIDTH_DP, HEIGHT_DP, Gravity.TOP or Gravity.END, 40, 40)) return
            view = it
        }
        card.set(title, artist, cover, hintOk)
        handler.postDelayed(hide, forMs)
    }

    fun remove() {
        handler.removeCallbacks(hide)
        val card = view ?: return
        view = null
        OverlayWindows.remove(service, card)
    }

    private class CardView(context: Context) : View(context) {
        private val paints = CardPaints(resources.displayMetrics.density)
        private var title = ""
        private var artist = ""
        private var cover: Bitmap? = null
        private var hint = false
        private val clip = Path()

        fun set(title: String, artist: String, cover: Bitmap?, hint: Boolean) {
            this.title = title
            this.artist = artist
            this.cover = cover
            this.hint = hint
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val d = paints.d
            val w = width.toFloat()
            val h = height.toFloat()
            paints.card(canvas, w, h, 20f)
            val pad = 14f * d
            val size = h - 2f * pad
            val bitmap = cover
            if (bitmap != null) {
                canvas.save()
                clip.reset()
                clip.addRoundRect(RectF(pad, pad, pad + size, pad + size), 10f * d, 10f * d, Path.Direction.CW)
                canvas.clipPath(clip)
                canvas.drawBitmap(bitmap, null, RectF(pad, pad, pad + size, pad + size), null)
                canvas.restore()
            } else {
                paints.fill.color = 0xFF3A3A3C.toInt()
                canvas.drawRoundRect(RectF(pad, pad, pad + size, pad + size), 10f * d, 10f * d, paints.fill)
                paints.fill.color = 0xF01C1C1E.toInt()
                paints.title.textAlign = Paint.Align.CENTER
                canvas.drawText("♪", pad + size / 2f, pad + size / 2f + 6f * d, paints.title)
                paints.title.textAlign = Paint.Align.LEFT
            }
            val x = pad + size + 14f * d
            val room = w - x - pad
            canvas.drawText(paints.fit(title, paints.title, room), x, h / 2f - 4f * d, paints.title)
            canvas.drawText(paints.fit(artist, paints.sub, room), x, h / 2f + 18f * d, paints.sub)
            if (hint) {
                paints.sub.textSize = 11f * d
                canvas.drawText("OK for the music remote", x, h - pad + 2f * d, paints.sub)
                paints.sub.textSize = 14f * d
            }
        }
    }

    private companion object {
        const val SHOW_MS = 4000L
        const val WIDTH_DP = 380
        const val HEIGHT_DP = 96
    }
}

/** One key press shows the time, the date, the weather the home screen last sent and what plays, for about four seconds. */
class GlanceCard(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var view: GlanceView? = null
    private val hide = Runnable { remove() }

    fun show(time: String, date: String, weather: String, playing: String, forMs: Long = SHOW_MS) {
        handler.removeCallbacks(hide)
        val card = view ?: GlanceView(service).also {
            if (!OverlayWindows.add(service, it, WIDTH_DP, HEIGHT_DP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 60)) return
            view = it
        }
        card.set(time, date, weather, playing)
        handler.postDelayed(hide, forMs)
    }

    fun remove() {
        handler.removeCallbacks(hide)
        val card = view ?: return
        view = null
        OverlayWindows.remove(service, card)
    }

    private class GlanceView(context: Context) : View(context) {
        private val paints = CardPaints(resources.displayMetrics.density)
        private var time = ""
        private var date = ""
        private var weather = ""
        private var playing = ""
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = 52f * paints.d
            textAlign = Paint.Align.CENTER
        }

        fun set(time: String, date: String, weather: String, playing: String) {
            this.time = time
            this.date = date
            this.weather = weather
            this.playing = playing
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val d = paints.d
            val w = width.toFloat()
            val h = height.toFloat()
            paints.card(canvas, w, h, 26f)
            val room = w - 40f * d
            canvas.drawText(time, w / 2f, 20f * d + 40f * d, big)
            paints.sub.textAlign = Paint.Align.CENTER
            val line = listOf(date, weather).filter { it.isNotEmpty() }.joinToString("   ·   ")
            canvas.drawText(paints.fit(line, paints.sub, room), w / 2f, 20f * d + 40f * d + 30f * d, paints.sub)
            if (playing.isNotEmpty()) {
                paints.sub.color = 0x88FFFFFF.toInt()
                canvas.drawText(paints.fit("♪  $playing", paints.sub, room), w / 2f, h - 18f * d, paints.sub)
                paints.sub.color = 0xB0FFFFFF.toInt()
            }
            paints.sub.textAlign = Paint.Align.LEFT
        }
    }

    private companion object {
        const val SHOW_MS = 4000L
        const val WIDTH_DP = 440
        const val HEIGHT_DP = 190
    }
}

/**
 * The music remote over any app: previous, play or pause, next for the phone's music, and the volume. Left and Right choose a button,
 * OK presses it, Up and Down move the volume, Back or Menu closes it; it closes by itself after [IDLE_MS] without a key.
 */
class MiniRemote(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var view: RemoteView? = null
    private var focus = 1
    private val idle = Runnable { close() }
    private val stateListener: (ReceiverState.Snapshot) -> Unit = { handler.post { refresh() } }

    val isOpen: Boolean get() = view != null

    fun open() {
        if (view != null) return
        val panel = RemoteView(service)
        if (!OverlayWindows.add(service, panel, WIDTH_DP, HEIGHT_DP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 70)) return
        view = panel
        focus = 1
        ReceiverState.observe(stateListener)
        refresh()
        touch()
    }

    fun close() {
        handler.removeCallbacks(idle)
        val panel = view ?: return
        view = null
        ReceiverState.remove(stateListener)
        OverlayWindows.remove(service, panel)
    }

    /** Offers a key while the remote is open; true when it was taken (press and release alike). */
    fun onKey(event: KeyEvent): Boolean {
        if (view == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            close()
            return false
        }
        val mine = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
            -> true
            else -> false
        }
        if (!mine) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        touch()
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> focus = (focus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> focus = (focus + 1).coerceAtMost(2)
            KeyEvent.KEYCODE_DPAD_UP -> VolumeBridge.step(true)
            KeyEvent.KEYCODE_DPAD_DOWN -> VolumeBridge.step(false)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) press()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) close()
        }
        refresh()
        return true
    }

    private fun press() {
        RemoteControl.send(
            when (focus) {
                0 -> DacpClient.PREVIOUS
                2 -> DacpClient.NEXT
                else -> DacpClient.PLAY_PAUSE
            },
        )
    }

    private fun touch() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IDLE_MS)
    }

    private fun refresh() {
        val panel = view ?: return
        val s = ReceiverState.current
        panel.set(s.title.ifEmpty { "Nothing is playing" }, s.artist, s.artwork, focus, s.playing && s.audioActive, s.volume)
    }

    private class RemoteView(context: Context) : View(context) {
        private val paints = CardPaints(resources.displayMetrics.density)
        private var title = ""
        private var artist = ""
        private var cover: Bitmap? = null
        private var focus = 1
        private var playing = true
        private var volume = -1f
        private val icon = Path()
        private val clip = Path()

        fun set(title: String, artist: String, cover: Bitmap?, focus: Int, playing: Boolean, volume: Float) {
            this.title = title
            this.artist = artist
            this.cover = cover
            this.focus = focus
            this.playing = playing
            this.volume = volume
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val d = paints.d
            val w = width.toFloat()
            val h = height.toFloat()
            paints.card(canvas, w, h, 26f)
            val pad = 18f * d
            val art = 64f * d
            val bitmap = cover
            if (bitmap != null) {
                canvas.save()
                clip.reset()
                clip.addRoundRect(RectF(pad, pad, pad + art, pad + art), 10f * d, 10f * d, Path.Direction.CW)
                canvas.clipPath(clip)
                canvas.drawBitmap(bitmap, null, RectF(pad, pad, pad + art, pad + art), null)
                canvas.restore()
            }
            val x = if (bitmap != null) pad + art + 14f * d else pad
            val room = w - x - pad
            canvas.drawText(paints.fit(title, paints.title, room), x, pad + 26f * d, paints.title)
            canvas.drawText(paints.fit(artist, paints.sub, room), x, pad + 50f * d, paints.sub)

            // the three buttons, the one in focus on a light pill
            val cy = pad + art + 40f * d
            val xs = floatArrayOf(w / 2f - 90f * d, w / 2f, w / 2f + 90f * d)
            for (i in 0..2) {
                val lit = i == focus
                if (lit) {
                    paints.fill.color = 0xF2F5F5F7.toInt()
                    paints.box.set(xs[i] - 34f * d, cy - 24f * d, xs[i] + 34f * d, cy + 24f * d)
                    canvas.drawRoundRect(paints.box, 24f * d, 24f * d, paints.fill)
                    paints.fill.color = 0xF01C1C1E.toInt()
                }
                val ink = if (lit) 0xFF1C1C1E.toInt() else Color.WHITE
                drawButton(canvas, i, xs[i], cy, ink, d)
            }

            // the volume
            val barY = h - 20f * d
            val left = pad
            val right = w - pad
            paints.fill.color = 0x33FFFFFF
            paints.box.set(left, barY - 3f * d, right, barY + 3f * d)
            canvas.drawRoundRect(paints.box, 3f * d, 3f * d, paints.fill)
            if (volume >= 0f) {
                paints.fill.color = Color.WHITE
                paints.box.set(left, barY - 3f * d, left + (right - left) * volume.coerceIn(0f, 1f), barY + 3f * d)
                canvas.drawRoundRect(paints.box, 3f * d, 3f * d, paints.fill)
            }
            paints.fill.color = 0xF01C1C1E.toInt()
        }

        private fun drawButton(canvas: Canvas, which: Int, cx: Float, cy: Float, ink: Int, d: Float) {
            paints.fill.color = ink
            val s = 11f * d
            icon.reset()
            when {
                which == 0 -> {
                    // previous: a bar and a triangle pointing left
                    canvas.drawRect(cx - s, cy - s, cx - s + 3f * d, cy + s, paints.fill)
                    icon.moveTo(cx + s, cy - s)
                    icon.lineTo(cx - s + 4f * d, cy)
                    icon.lineTo(cx + s, cy + s)
                    icon.close()
                    canvas.drawPath(icon, paints.fill)
                }
                which == 2 -> {
                    icon.moveTo(cx - s, cy - s)
                    icon.lineTo(cx + s - 4f * d, cy)
                    icon.lineTo(cx - s, cy + s)
                    icon.close()
                    canvas.drawPath(icon, paints.fill)
                    canvas.drawRect(cx + s - 3f * d, cy - s, cx + s, cy + s, paints.fill)
                }
                playing -> {
                    // pause: two bars
                    canvas.drawRect(cx - s * 0.7f, cy - s, cx - s * 0.2f, cy + s, paints.fill)
                    canvas.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.7f, cy + s, paints.fill)
                }
                else -> {
                    icon.moveTo(cx - s * 0.6f, cy - s)
                    icon.lineTo(cx + s, cy)
                    icon.lineTo(cx - s * 0.6f, cy + s)
                    icon.close()
                    canvas.drawPath(icon, paints.fill)
                }
            }
            paints.fill.color = 0xF01C1C1E.toInt()
        }
    }

    private companion object {
        const val IDLE_MS = 8_000L
        const val WIDTH_DP = 460
        const val HEIGHT_DP = 190
    }
}

/** What the app switcher shows for one app. */
class SwitcherEntry(val packageName: String, val label: String, val icon: Drawable)

/**
 * A row of the last apps used, over the screen: Left and Right choose, OK opens that app, Back, Menu or Home closes it, and it closes by itself
 * after [IDLE_MS] without a key.
 */
class AppSwitcher(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var view: SwitcherView? = null
    private var entries: List<SwitcherEntry> = emptyList()
    private var focus = 0
    private val idle = Runnable { close() }

    val isOpen: Boolean get() = view != null

    fun open(items: List<SwitcherEntry>, onOpen: (String) -> Unit) {
        if (view != null || items.isEmpty()) return
        entries = items
        this.onOpen = onOpen
        val panel = SwitcherView(service)
        val widthDp = 36 + items.size * TILE_DP
        if (!OverlayWindows.add(service, panel, widthDp, HEIGHT_DP, Gravity.CENTER, 0, 0)) return
        view = panel
        focus = 0
        refresh()
        touch()
    }

    private var onOpen: (String) -> Unit = {}

    fun close() {
        handler.removeCallbacks(idle)
        val panel = view ?: return
        view = null
        OverlayWindows.remove(service, panel)
    }

    fun onKey(event: KeyEvent): Boolean {
        if (view == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            close()
            return false
        }
        val mine = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
            -> true
            else -> false
        }
        if (!mine) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        touch()
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> focus = (focus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> focus = (focus + 1).coerceAtMost(entries.size - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) {
                val pick = entries.getOrNull(focus)
                close()
                if (pick != null) onOpen(pick.packageName)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) {
                close()
                return true
            }
        }
        refresh()
        return true
    }

    private fun touch() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IDLE_MS)
    }

    private fun refresh() {
        view?.set(entries, focus)
    }

    private class SwitcherView(context: Context) : View(context) {
        private val paints = CardPaints(resources.displayMetrics.density)
        private var entries: List<SwitcherEntry> = emptyList()
        private var focus = 0

        fun set(entries: List<SwitcherEntry>, focus: Int) {
            this.entries = entries
            this.focus = focus
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val d = paints.d
            val w = width.toFloat()
            val h = height.toFloat()
            paints.card(canvas, w, h, 26f)
            paints.sub.textAlign = Paint.Align.CENTER
            entries.forEachIndexed { i, entry ->
                val left = 18f * d + i * TILE_DP * d
                val cx = left + TILE_DP * d / 2f
                if (i == focus) {
                    paints.fill.color = 0x40FFFFFF
                    paints.box.set(left + 4f * d, 14f * d, left + TILE_DP * d - 4f * d, h - 14f * d)
                    canvas.drawRoundRect(paints.box, 18f * d, 18f * d, paints.fill)
                    paints.fill.color = 0xF01C1C1E.toInt()
                }
                val size = (64f * d).toInt()
                entry.icon.setBounds((cx - size / 2f).toInt(), (24f * d).toInt(), (cx + size / 2f).toInt(), (24f * d).toInt() + size)
                entry.icon.draw(canvas)
                canvas.drawText(paints.fit(entry.label, paints.sub, TILE_DP * d - 14f * d), cx, h - 26f * d, paints.sub)
            }
            paints.sub.textAlign = Paint.Align.LEFT
        }
    }

    private companion object {
        const val IDLE_MS = 10_000L
        const val TILE_DP = 118
        const val HEIGHT_DP = 150
    }
}
