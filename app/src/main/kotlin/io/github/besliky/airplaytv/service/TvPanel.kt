package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.lg.LgFacts
import io.github.besliky.airplaytv.lg.TvControl
import io.github.besliky.airplaytv.lg.TvMenuMode
import io.github.besliky.airplaytv.lg.TvPicture
import io.github.besliky.airplaytv.lg.TvScenes
import io.github.besliky.airplaytv.lg.TvSound
import io.github.besliky.airplaytv.lg.cycle

/**
 * The TV quick panel: one list over whatever is on the screen, opened by pressing the Menu key twice. Up and Down move, Left and Right
 * change a value, OK does the thing, Back or Menu closes it. It reads the TV when it opens (the input in front, the picture mode, the sound
 * output, the volume) and changes nothing until a row is used. Like the volume bar it is a window of the accessibility service and takes
 * its keys from the service, so it needs no focus and no permission, and after [IDLE_MS] without a key it closes by itself.
 *
 * All of it runs on the main thread; what has to wait for the TV runs on [TvOps] and comes back through it.
 */
class TvPanel(private val service: AccessibilityService) {

    private enum class Kind { SETTINGS, SCENE, INPUT, PICTURE, SOUND, VOLUME, MUSIC, SCREEN, SLEEP, POWER }

    private val kinds = Kind.entries
    private val handler = Handler(Looper.getMainLooper())
    private val settings = Settings(service)
    private var view: PanelView? = null

    @Volatile
    private var control: TvControl? = null

    private var focus = 0
    private var loading = true
    private var note: String? = null
    private var state: TvControl.State? = null
    private var sceneIdx = 0
    private var inputChoices: List<Pair<String, String>> = emptyList() // the TV's input id and what the panel calls it
    private var inputIdx = 0
    private var pictureId: String? = null
    private var soundId: String? = null
    private var volume = -1
    private var volumeFixed = false
    private var sleepIdx = 0
    private var powerArmed = false

    val isOpen: Boolean get() = view != null

    private val idle = Runnable { close() }

    private val applyPicture = Runnable {
        val id = pictureId ?: return@Runnable
        TvOps.run("panel picture") {
            val tv = control ?: return@run
            val ok = tv.setPicture(id)
            TvOps.onMain { if (!ok) say("The TV did not take ${TvPicture.label(id)}") }
        }
    }

    private val applySound = Runnable {
        val id = soundId ?: return@Runnable
        TvOps.run("panel sound") {
            val tv = control ?: return@run
            val ok = tv.setSound(id)
            TvOps.onMain { if (!ok) say("The TV did not take ${TvSound.label(id)}") }
        }
    }

    private val applyVolume = Runnable {
        val level = volume
        if (level < 0) return@Runnable
        TvOps.run("panel volume") {
            val tv = control ?: return@run
            val ok = tv.setVolume(level)
            TvOps.onMain { if (!ok) say("The TV's volume did not move") }
        }
    }

    fun open() {
        if (view != null) return
        val panel = PanelView(service)
        if (!add(panel)) return
        view = panel
        focus = 0
        loading = true
        note = null
        state = null
        powerArmed = false
        sleepIdx = 0
        refresh()
        touch()
        TvOps.run("panel open") {
            val tv = TvControl.open(settings)
            if (tv == null) {
                TvOps.onMain {
                    loading = false
                    note = "The TV did not answer. Is it on?"
                    refresh()
                }
                return@run
            }
            control = tv
            val read = tv.read()
            TvOps.onMain { adopt(read) }
        }
    }

    fun close() {
        handler.removeCallbacks(idle)
        for (pending in listOf(applyPicture, applySound, applyVolume)) handler.removeCallbacks(pending)
        val panel = view ?: return
        view = null
        try {
            windows().removeView(panel)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot take the panel away", e)
        }
        TvOps.run("panel close") {
            control?.close()
            control = null
        }
    }

    /** Offers a key to the panel while it is open. True when the key was taken (press and release alike). */
    fun onKey(event: KeyEvent): Boolean {
        if (view == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            close()
            return false
        }
        val mine = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
            -> true
            else -> false
        }
        if (!mine) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        touch()
        note = null
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> change(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> change(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) activate()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) close()
        }
        return true
    }

    // ---- what the TV said

    private fun adopt(read: TvControl.State) {
        if (view == null) return
        state = read
        loading = false
        inputChoices = read.connectedInputs.mapNotNull { input ->
            val id = LgFacts.inputIdOf(input.appId) ?: return@mapNotNull null
            id to inputName(id, input.label)
        }
        inputIdx = inputChoices.indexOfFirst { it.first == read.inputId }.coerceAtLeast(0)
        pictureId = read.picture
        soundId = read.sound
        volume = read.volume?.level ?: -1
        volumeFixed = read.volume?.adjustable == false
        refresh()
    }

    private fun inputName(id: String, label: String): String {
        val number = id.replace('_', ' ')
        val plain = label.trim()
        return if (plain.isEmpty() || plain.equals(number, ignoreCase = true) || plain.replace(" ", "").equals(number.replace(" ", ""), ignoreCase = true)) {
            number
        } else {
            "$number · $plain"
        }
    }

    // ---- keys

    private fun move(step: Int) {
        focus = Math.floorMod(focus + step, kinds.size)
        powerArmed = false
        refresh()
    }

    private fun change(step: Int) {
        powerArmed = false
        when (kinds[focus]) {
            Kind.SCENE -> sceneIdx = Math.floorMod(sceneIdx + step, TvScenes.IDS.size)
            Kind.INPUT -> if (inputChoices.isNotEmpty()) inputIdx = Math.floorMod(inputIdx + step, inputChoices.size)
            Kind.PICTURE -> if (!loading && control != null) {
                pictureId = cycle(pictureChoices(), pictureId, step)
                later(applyPicture, SETTLE_MS)
            }
            Kind.SOUND -> if (!loading && control != null) {
                soundId = cycle(soundChoices(), soundId, step)
                later(applySound, SETTLE_MS)
            }
            Kind.VOLUME -> when {
                volumeFixed -> note = "This TV's volume is fixed (its sound goes to an external output)"
                volume >= 0 -> {
                    volume = (volume + step).coerceIn(0, 100)
                    later(applyVolume, VOLUME_SETTLE_MS)
                }
            }
            Kind.MUSIC -> toggleMusicMode()
            Kind.SLEEP -> sleepIdx = Math.floorMod(sleepIdx + step, SleepTimerMath.CHOICES.size)
            else -> Unit
        }
        refresh()
    }

    private fun activate() {
        when (kinds[focus]) {
            Kind.SETTINGS -> {
                close()
                TvMenuMode.toggle(service)
            }
            Kind.SCENE -> {
                val id = TvScenes.IDS[sceneIdx]
                close()
                TvActions.applyScene(service, id)
            }
            Kind.INPUT -> {
                val choice = inputChoices.getOrNull(inputIdx) ?: return
                close()
                TvActions.switchInput(service, choice.first, choice.second)
            }
            Kind.PICTURE -> {
                handler.removeCallbacks(applyPicture)
                applyPicture.run()
            }
            Kind.SOUND -> {
                handler.removeCallbacks(applySound)
                applySound.run()
            }
            Kind.VOLUME -> Unit
            Kind.MUSIC -> toggleMusicMode()
            Kind.SCREEN -> {
                close()
                TvActions.screenOff(service)
            }
            Kind.SLEEP -> {
                TvSleepTimer.start(service, SleepTimerMath.CHOICES[sleepIdx])
                say(if (SleepTimerMath.CHOICES[sleepIdx] == 0) "Sleep timer off" else "Sleep timer set")
            }
            Kind.POWER -> {
                if (!powerArmed) {
                    powerArmed = true
                } else {
                    close()
                    TvActions.tvOff(service)
                    return
                }
            }
        }
        refresh()
    }

    private fun toggleMusicMode() {
        settings.musicMode = !settings.musicMode
        val s = ReceiverState.current
        if (settings.musicMode && s.audioActive && !s.videoActive) {
            close()
            TvActions.screenOff(service)
        }
    }

    private fun pictureChoices(): List<String> = TvPicture.MODES.map { it.id }.let { list -> pictureId?.takeIf { it !in list }?.let { list + it } ?: list }

    private fun soundChoices(): List<String> = TvSound.OUTPUTS.map { it.id }.let { list -> soundId?.takeIf { it !in list }?.let { list + it } ?: list }

    private fun later(run: Runnable, delayMs: Long) {
        handler.removeCallbacks(run)
        handler.postDelayed(run, delayMs)
    }

    private fun say(text: String) {
        note = text
        refresh()
    }

    private fun touch() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IDLE_MS)
    }

    // ---- what is drawn

    private fun refresh() {
        val panel = view ?: return
        val items = kinds.map { kind ->
            when (kind) {
                Kind.SETTINGS -> PanelView.Item("LG TV Settings", "", false)
                Kind.SCENE -> PanelView.Item("Scene", TvScenes.label(TvScenes.IDS[sceneIdx]), true)
                Kind.INPUT -> PanelView.Item("Input", inputChoices.getOrNull(inputIdx)?.second ?: dash(), inputChoices.size > 1)
                Kind.PICTURE -> PanelView.Item("Picture Mode", pictureId?.let { TvPicture.label(it) } ?: dash(), pictureId != null)
                Kind.SOUND -> PanelView.Item("Sound Output", soundId?.let { TvSound.label(it) } ?: dash(), soundId != null)
                Kind.VOLUME -> PanelView.Item("Volume", if (volume >= 0) "$volume" else dash(), volume >= 0 && !volumeFixed)
                Kind.MUSIC -> PanelView.Item("Music Mode (Screen Off)", if (settings.musicMode) "On" else "Off", true)
                Kind.SCREEN -> PanelView.Item("Screen Off Now", "", false)
                Kind.SLEEP -> PanelView.Item("Sleep Timer", sleepValue(), true)
                Kind.POWER -> PanelView.Item(if (powerArmed) "Press OK Again To Turn The TV Off" else "Turn The TV Off", "", false, danger = powerArmed)
            }
        }
        panel.show(statusLine(), note != null, items, focus)
    }

    private fun dash() = if (loading) "…" else "—"

    private fun sleepValue(): String {
        val minutes = SleepTimerMath.CHOICES[sleepIdx]
        val running = TvSleepTimer.endsAt(service).takeIf { it > System.currentTimeMillis() }
        val choice = if (minutes == 0) "Off" else "$minutes min"
        return if (running != null) "$choice · ${SleepTimerMath.left(System.currentTimeMillis(), running)} left" else choice
    }

    private fun statusLine(): String {
        note?.let { return it }
        if (loading) return "Asking the TV…"
        val read = state ?: return ""
        val parts = ArrayList<String>()
        inputChoices.firstOrNull { it.first == read.inputId }?.let { parts.add(it.second) }
        pictureId?.let { parts.add(TvPicture.label(it)) }
        soundId?.let { parts.add(TvSound.label(it)) }
        if (volume >= 0) parts.add("Vol $volume")
        if (LgFacts.isOnStandby(read.power) || read.power?.contains("Screen Off", ignoreCase = true) == true) parts.add("Screen off")
        return parts.joinToString("  ·  ")
    }

    private fun windows() = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun add(panel: PanelView): Boolean {
        val size = panel.measureFor(kinds.size, service.resources.displayMetrics.heightPixels)
        val params = WindowManager.LayoutParams(
            size.first,
            size.second,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        return try {
            windows().addView(panel, params)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot show the panel", e)
            false
        }
    }

    /** The panel itself: a dark rounded card with the status line above a list of rows, the one in focus on a lighter pill. */
    private class PanelView(context: Context) : View(context) {

        class Item(val label: String, val value: String, val arrows: Boolean, val danger: Boolean = false)

        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var header = ""
        private var headerAmber = false
        private var items: List<Item> = emptyList()
        private var focus = 0
        private var rowHeight = 40f * density
        private val headerHeight = 46f * density
        private val padding = 14f * density

        /** The window's width and height in pixels for [rows] rows on a screen [screenHeight] pixels high. */
        fun measureFor(rows: Int, screenHeight: Int): Pair<Int, Int> {
            val room = screenHeight * 0.9f - headerHeight - 2 * padding
            rowHeight = minOf(40f * density, room / rows)
            return Pair((WIDTH_DP * density).toInt(), (headerHeight + rows * rowHeight + 2 * padding).toInt())
        }

        fun show(header: String, amber: Boolean, items: List<Item>, focus: Int) {
            this.header = header
            this.headerAmber = amber
            this.items = items
            this.focus = focus
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = 0xF01C1C1E.toInt()
            canvas.drawRoundRect(0f, 0f, w, h, 22f * density, 22f * density, paint)

            paint.typeface = Typeface.DEFAULT
            paint.textSize = 15f * density
            paint.color = if (headerAmber) AMBER else 0xFFB0B0B8.toInt()
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(fit(header, w - 2 * padding), w / 2f, padding + headerHeight * 0.55f, paint)

            var top = padding + headerHeight
            for ((index, item) in items.withIndex()) {
                if (index == focus) {
                    paint.color = 0x38FFFFFF
                    canvas.drawRoundRect(padding * 0.6f, top, w - padding * 0.6f, top + rowHeight, rowHeight / 2f, rowHeight / 2f, paint)
                }
                val baseline = top + rowHeight / 2f + 6f * density
                paint.textSize = 17f * density
                paint.textAlign = Paint.Align.LEFT
                paint.typeface = if (index == focus) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paint.color = if (item.danger) AMBER else Color.WHITE
                canvas.drawText(item.label, padding + 12f * density, baseline, paint)
                if (item.value.isNotEmpty()) {
                    paint.textAlign = Paint.Align.RIGHT
                    paint.typeface = Typeface.DEFAULT
                    paint.color = if (index == focus) Color.WHITE else 0xFFB0B0B8.toInt()
                    val text = if (index == focus && item.arrows) "‹  ${item.value}  ›" else item.value
                    canvas.drawText(text, w - padding - 12f * density, baseline, paint)
                }
                top += rowHeight
            }
        }

        private fun fit(text: String, room: Float): String {
            if (paint.measureText(text) <= room) return text
            var end = text.length
            while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > room) end--
            return text.substring(0, end) + "…"
        }

        private companion object {
            const val WIDTH_DP = 560
            const val AMBER = 0xFFFFB340.toInt()
        }
    }

    private companion object {
        const val TAG = "AirPlayTV-Panel"
        const val IDLE_MS = 20_000L
        const val SETTLE_MS = 600L
        const val VOLUME_SETTLE_MS = 180L
    }
}
