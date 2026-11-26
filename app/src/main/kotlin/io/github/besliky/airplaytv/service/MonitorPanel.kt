package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import io.github.besliky.airplaytv.Settings

/**
 * The quick panel of monitor mode, opened with the Menu button: what the stick can do for a screen it cannot talk to. Up and Down
 * move, Left and Right change a value, OK does the thing, Back or Menu closes it.
 */
class MonitorPanel(private val service: AccessibilityService, private val display: MonitorDisplay) {

    private enum class Kind { VOLUME, MUTE, BRIGHTNESS, WARMTH, REMOTE, BLANK, SLEEP_TIMER, SLEEP, SETTINGS }

    private val handler = Handler(Looper.getMainLooper())
    private val settings = Settings(service)
    private var view: TvPanel.PanelView? = null
    private var kinds: List<Kind> = emptyList()
    private var focus = 0
    private var sleepIdx = 0

    val isOpen: Boolean get() = view != null

    private val idle = Runnable { close() }

    fun open() {
        if (view != null) return
        kinds = Kind.entries.filter { it != Kind.REMOTE || settings.tvMiniRemote }
        val panel = TvPanel.PanelView(service)
        val size = panel.measureFor(kinds.size, service.resources.displayMetrics.heightPixels)
        val density = service.resources.displayMetrics.density
        val widthDp = (size.first / density).toInt()
        val heightDp = (size.second / density).toInt()
        if (!OverlayWindows.add(service, panel, widthDp, heightDp, Gravity.CENTER)) return
        view = panel
        focus = 0
        sleepIdx = 0
        display.barSuppressed = true
        display.onChanged = { refresh() }
        refresh()
        touch()
    }

    fun close() {
        handler.removeCallbacks(idle)
        val panel = view ?: return
        view = null
        display.barSuppressed = false
        display.onChanged = null
        OverlayWindows.remove(service, panel)
    }

    /** Offers a key to the open panel. True when the key was taken (press and release alike). */
    fun onKey(event: KeyEvent): Boolean {
        if (view == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            close()
            return false
        }
        val mine = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
            -> true
            else -> false
        }
        if (!mine) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        touch()
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> focus = Math.floorMod(focus - 1, kinds.size)
            KeyEvent.KEYCODE_DPAD_DOWN -> focus = Math.floorMod(focus + 1, kinds.size)
            KeyEvent.KEYCODE_DPAD_LEFT -> change(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> change(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) activate()
            KeyEvent.KEYCODE_BACK -> if (event.repeatCount == 0) close()
        }
        refresh()
        return true
    }

    private fun change(step: Int) {
        when (kinds[focus]) {
            Kind.VOLUME -> display.stepVolume(step)
            Kind.MUTE -> display.toggleMute()
            Kind.BRIGHTNESS -> display.brightness = (display.brightness + step).coerceIn(0, MonitorScale.MAX_BRIGHTNESS)
            Kind.WARMTH -> display.warmth = (display.warmth + step).coerceIn(0, MonitorScale.MAX_WARMTH)
            Kind.SLEEP_TIMER -> sleepIdx = Math.floorMod(sleepIdx + step, SleepTimerMath.CHOICES.size)
            else -> Unit
        }
    }

    private fun activate() {
        when (kinds[focus]) {
            Kind.MUTE -> display.toggleMute()
            Kind.REMOTE -> {
                close()
                MenuKeyService.openMiniRemote()
            }
            Kind.BLANK -> {
                close()
                display.blank(true)
            }
            Kind.SLEEP_TIMER -> {
                TvSleepTimer.start(service, SleepTimerMath.CHOICES[sleepIdx])
                close()
            }
            Kind.SLEEP -> {
                close()
                MenuKeyService.sleepNow()
            }
            Kind.SETTINGS -> {
                close()
                openSettings()
            }
            else -> Unit
        }
    }

    /** Fire OS answers the usual settings action with a stub from a third-party app; this is the real page. */
    private fun openSettings() {
        val intent = Intent("amazon.intent.action.MAINSETTINGS")
            .addCategory("android.intent.category.MAINSETTINGS")
            .setComponent(ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            service.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(SERVICE, "cannot open the settings", e)
        }
    }

    private fun touch() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IDLE_MS)
    }

    private fun refresh() {
        val panel = view ?: return
        val items = kinds.map { kind ->
            when (kind) {
                Kind.VOLUME -> TvPanel.PanelView.Item("Volume", "${display.volume}", true)
                Kind.MUTE -> TvPanel.PanelView.Item("Mute", if (display.muted) "On" else "Off", true)
                Kind.BRIGHTNESS -> TvPanel.PanelView.Item("Brightness", "${display.brightness * 10}%", true)
                Kind.WARMTH -> TvPanel.PanelView.Item("Night Warmth", if (display.warmth == 0) "Off" else "${display.warmth}", true)
                Kind.REMOTE -> TvPanel.PanelView.Item("Music Remote", "", false)
                Kind.BLANK -> TvPanel.PanelView.Item("Screen Off (Sound Goes On)", "", false)
                Kind.SLEEP_TIMER -> TvPanel.PanelView.Item("Sleep Timer", sleepValue(), true)
                Kind.SLEEP -> TvPanel.PanelView.Item("Sleep Now", "", false)
                Kind.SETTINGS -> TvPanel.PanelView.Item("Fire TV Settings", "", false)
            }
        }
        val header = if (display.needsPermission) "Volume needs setup (DUMP permission)" else "Monitor"
        panel.show(header, display.needsPermission, items, focus)
    }

    private fun sleepValue(): String {
        val minutes = SleepTimerMath.CHOICES[sleepIdx]
        val running = TvSleepTimer.endsAt(service).takeIf { it > System.currentTimeMillis() }
        val choice = if (minutes == 0) "Off" else "$minutes min"
        return if (running != null) "$choice · ${SleepTimerMath.left(System.currentTimeMillis(), running)} left" else choice
    }

    private companion object {
        const val IDLE_MS = 20_000L
    }
}
