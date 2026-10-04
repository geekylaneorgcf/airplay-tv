package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.widget.Toast
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.Date
import java.util.Locale
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.lg.TvMenuMode
import io.github.besliky.airplaytv.lg.TvSettings
import io.github.besliky.airplaytv.ui.NowPlayingActivity

/**
 * Lets the Menu button (the three bars next to Home) open the LG TV's own settings in every app, and, pressed twice, the quick panel (see
 * [TvPanel]); held, it does what the owner chose (nothing, unless chosen). Android
 * only lets an accessibility service see a key before the app in front of it does, so the button has to
 * be taken here; with the TV Settings Button off the service lets every key through untouched.
 *
 * It looks at the Menu key (and, while the receiver asks for them, the volume keys: see [VolumeKeys]; and the Home key, only while Hold Home is
 * switched on) and nothing else, reads no screen content and, unless Hold Home is on, listens to no events (with it on, only to which app has
 * come to the front, to keep the list of the last five). While AirPlay plays and the volume is moved, it also draws a small volume bar (see
 * [VolumeOverlay]) over any screen but the player's own; the cards the owner switched on (see [NowPlayingCard], [GlanceCard], [MiniRemote],
 * [AppSwitcher]) are drawn the same way. It is off
 * until the owner switches it on once from a computer, see the TV Settings Button setting. It is also what
 * puts the stick to sleep for Sleep After Music (see [SleepService]), so one switch does both.
 */
class MenuKeyService : AccessibilityService() {

    private var overlay: VolumeOverlay? = null
    private var panel: TvPanel? = null
    private var nowCard: NowPlayingCard? = null
    private var glanceCard: GlanceCard? = null
    private var miniRemote: MiniRemote? = null
    private var switcher: AppSwitcher? = null
    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())

    // Hold Home: the app in front, the last apps, and the short time after the service itself pressed Home in which the key is let through
    private var frontPackage: String? = null
    private var recents: List<String> = emptyList()
    private var passHomeUntil = 0L
    private var lastSongKey = ""

    /** Tells a press of Home from a hold of it (Hold Home only); a press goes on to the system as Home, a hold opens the switcher. */
    private val homeGesture = MenuGesture(
        post = { delayMs, run -> main.postDelayed(run, delayMs) },
        cancel = { run -> main.removeCallbacks(run) },
        doublePressWanted = { false },
        holdWanted = { true },
        closeOpen = { false },
        onAction = { onHomeAction(it) },
    )

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Settings.KEY_HOLD_HOME) applyEventTypes()
    }

    /** Tells a single press of the Menu key from a double press and a hold, see [MenuGesture]. */
    private val gesture = MenuGesture(
        post = { delayMs, run -> main.postDelayed(run, delayMs) },
        cancel = { run -> main.removeCallbacks(run) },
        doublePressWanted = { settings.tvPanelDoublePress },
        holdWanted = { settings.tvHoldAction != HOLD_NOTHING },
        closeOpen = { closeOpen() },
        onAction = { onMenuAction(it) },
    )
    private var lastVolumeAt = 0L
    private var lastLevel = -1f

    /** Shows the volume bar when the level moves in a session (the first report of a session is not a move). */
    private val volumeListener: (ReceiverState.Snapshot) -> Unit = { s ->
        if (s.status != ReceiverState.Status.CONNECTED) {
            lastLevel = -1f
        } else if (s.volumeAtMs != lastVolumeAt) {
            val moved = lastLevel >= 0f && s.volume >= 0f && s.volume != lastLevel
            lastVolumeAt = s.volumeAtMs
            lastLevel = s.volume
            if (moved && !NowPlayingActivity.onScreen) overlay?.show(s.volume)
        }
    }

    /** A new song while another app is in front: the card with the cover comes up a moment later, when the cover has arrived (Now Playing Glance only). */
    private val songListener: (ReceiverState.Snapshot) -> Unit = { s ->
        if (s.status != ReceiverState.Status.CONNECTED || !s.audioActive || s.title.isEmpty()) {
            lastSongKey = ""
        } else if (settings.tvGlance) {
            val key = "${s.title}|${s.artist}"
            if (key != lastSongKey) {
                lastSongKey = key
                main.postDelayed({ showSongCard() }, SONG_CARD_DELAY_MS)
            }
        }
    }

    private fun showSongCard() {
        val s = ReceiverState.current
        if (!settings.tvGlance || !s.audioActive || s.title.isEmpty() || NowPlayingActivity.onScreen) return
        nowCard?.show(s.title, s.artist, s.artwork, hintOk = settings.tvMiniRemote)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = Settings(this)
        overlay = VolumeOverlay(this)
        panel = TvPanel(this)
        nowCard = NowPlayingCard(this)
        glanceCard = GlanceCard(this)
        miniRemote = MiniRemote(this)
        switcher = AppSwitcher(this)
        TvNotices.attach(this, TvBanner(this))
        TvActions.appContext = applicationContext
        TvSleepTimer.resume(this)
        recents = RecentApps.parse(settings.recentApps)
        applyEventTypes()
        settings.registerListener(settingsListener)
        ReceiverState.observe(volumeListener)
        ReceiverState.observe(songListener)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        ReceiverState.remove(volumeListener)
        ReceiverState.remove(songListener)
        settings.unregisterListener(settingsListener)
        overlay?.remove()
        overlay = null
        panel?.close()
        panel = null
        nowCard?.remove()
        nowCard = null
        glanceCard?.remove()
        glanceCard = null
        miniRemote?.close()
        miniRemote = null
        switcher?.close()
        switcher = null
        TvNotices.detach()
        return super.onUnbind(intent)
    }

    /**
     * The service listens to events only while Hold Home is on, and then only to which window has come to the front, to keep the list of
     * the last apps (no screen content). With it off it listens to none.
     */
    private fun applyEventTypes() {
        val info = serviceInfo ?: return
        val wanted = if (settings.holdHome) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0
        if (info.eventTypes == wanted) return
        info.eventTypes = wanted
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !settings.holdHome) return
        val pkg = event.packageName?.toString()
        if (!RecentApps.counts(pkg, packageName)) return
        if (pkg == frontPackage) return
        frontPackage = pkg
        recents = RecentApps.add(recents, pkg!!)
        settings.recentApps = RecentApps.encode(recents)
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (!VolumeKeys.isVolumeKey(event.keyCode)) UserKeys.listener?.invoke()
            TvIdle.touch()
            TvActions.onAnyKey()
            TvSleepTimer.onKey(this)
        }
        // The volume keys, while the receiver has asked for them (the TV owns the volume); otherwise they go on to the system.
        if (VolumeKeys.onKey(event.keyCode, event.action)) return true
        // Home, only with Hold Home on: a press goes on as Home, a hold opens the list of the last apps.
        if (event.keyCode == KeyEvent.KEYCODE_HOME && settings.holdHome && SystemClock.elapsedRealtime() >= passHomeUntil) {
            closeCards()
            if (event.action == KeyEvent.ACTION_DOWN) homeGesture.onDown(event.repeatCount) else if (event.action == KeyEvent.ACTION_UP) homeGesture.onUp()
            return true
        }
        // Menu: a press opens the TV's own menu, a double press the quick panel, a hold what the owner chose (see MenuGesture).
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (!settings.tvMenuButton) return false
            if (event.action == KeyEvent.ACTION_DOWN) gesture.onDown(event.repeatCount) else if (event.action == KeyEvent.ACTION_UP) gesture.onUp()
            return true // the press and its release belong to the TV now
        }
        // The cards that take keys: the app switcher, the music remote; OK on the song card opens the music remote.
        if (switcher?.isOpen == true) return switcher?.onKey(event) == true
        if (miniRemote?.isOpen == true) return miniRemote?.onKey(event) == true
        if (settings.tvMiniRemote && nowCard?.isShowing == true && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                nowCard?.remove()
                miniRemote?.open()
            }
            return true
        }
        // While the panel is open it takes the arrow keys, OK and Back; while the TV's menu is open the same keys steer that.
        if (panel?.isOpen == true) return panel?.onKey(event) == true
        if (!TvMenuMode.isActive) return false
        return TvMenuMode.onKey(this, event)
    }

    private fun closeCards() {
        switcher?.close()
        miniRemote?.close()
        glanceCard?.remove()
        nowCard?.remove()
    }

    private fun onHomeAction(action: MenuGesture.Action) {
        when (action) {
            MenuGesture.Action.HOLD -> openSwitcher()
            else -> homeNow()
        }
    }

    /** Home as the system does it; the key service lets its own Home through (see [passHomeUntil]). */
    private fun homeNow(): Boolean {
        passHomeUntil = SystemClock.elapsedRealtime() + PASS_HOME_MS
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun openSwitcher() {
        val pm = packageManager
        fun launchable(pkg: String) = pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null
        val items = RecentApps.choices(recents, frontPackage) { launchable(it) }.mapNotNull { pkg ->
            try {
                SwitcherEntry(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), pm.getApplicationIcon(pkg))
            } catch (_: Exception) {
                null
            }
        }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.switcher_empty, Toast.LENGTH_SHORT).show()
            return
        }
        switcher?.open(items) { pkg ->
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                try {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.switcher_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** The time, date, weather (from the home screen, when it is less than three hours old) and what plays, for a few seconds. */
    private fun showGlance() {
        val now = Date()
        val weatherFresh = System.currentTimeMillis() - settings.launcherWeatherAt < WEATHER_FRESH_MS
        val s = ReceiverState.current
        val playing = if (s.audioActive && s.title.isNotEmpty()) listOf(s.title, s.artist).filter { it.isNotEmpty() }.joinToString(" · ") else ""
        glanceCard?.show(
            DateFormat.getTimeFormat(this).format(now),
            java.text.SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now),
            if (weatherFresh) settings.launcherWeather else "",
            playing,
        )
    }

    /** A press of Menu while the panel or the TV's menu is open closes it (and does nothing else). */
    private fun closeOpen(): Boolean {
        if (miniRemote?.isOpen == true) {
            miniRemote?.close()
            return true
        }
        if (switcher?.isOpen == true) {
            switcher?.close()
            return true
        }
        if (panel?.isOpen == true) {
            panel?.close()
            return true
        }
        if (TvMenuMode.isActive) {
            TvMenuMode.toggle(this)
            return true
        }
        return false
    }

    private fun onMenuAction(action: MenuGesture.Action) {
        when (action) {
            MenuGesture.Action.SINGLE -> TvMenuMode.toggle(this)
            MenuGesture.Action.DOUBLE -> openPanel()
            MenuGesture.Action.HOLD -> when (settings.tvHoldAction) {
                HOLD_TV_OFF -> if (readyForTv()) TvActions.holdTvOff(this)
                HOLD_PANEL -> openPanel()
                HOLD_GAME -> if (readyForTv()) TvActions.applyScene(this, "game")
                HOLD_GLANCE -> if (settings.tvGlanceInfo) showGlance() else Toast.makeText(this, R.string.glance_off, Toast.LENGTH_LONG).show()
                HOLD_REMOTE -> if (settings.tvMiniRemote) miniRemote?.open() else Toast.makeText(this, R.string.mini_remote_off, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readyForTv(): Boolean {
        if (TvSettings.state(settings) == TvSettings.State.READY) return true
        Toast.makeText(this, R.string.tv_menu_needs_setup, Toast.LENGTH_LONG).show()
        return false
    }

    private fun openPanel() {
        if (readyForTv()) panel?.open()
    }

    companion object {
        private const val HOLD_NOTHING = 0
        private const val HOLD_TV_OFF = 1
        private const val HOLD_PANEL = 2
        private const val HOLD_GAME = 3
        private const val HOLD_GLANCE = 4
        private const val HOLD_REMOTE = 5
        private const val PASS_HOME_MS = 800L
        private const val SONG_CARD_DELAY_MS = 700L
        private const val WEATHER_FRESH_MS = 3 * 3_600_000L

        @Volatile
        private var instance: MenuKeyService? = null

        /** True while the owner has switched the service on. */
        val isEnabled: Boolean get() = instance != null

        /** Puts the device to sleep (the screen turns off and the TV follows over HDMI-CEC); false if the service is off. */
        fun sleepNow(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < 28) return false
            return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }

        /**
         * Presses Home, as the remote's button does (the service injects the key). On this stick a Home press makes its own HDMI-CEC send
         * "Text View On" and "Active Source", which switches a sleeping TV on and puts it on the stick: that is why the owner's Home press
         * wakes the TV when AirPlay does not (seen in `dumpsys hdmi_control`). False if the service is off.
         */
        fun pressHome(): Boolean {
            val service = instance ?: return false
            return service.homeNow()
        }

        /** Opens the quick panel (the home screen asks for it); nothing when the service is off or the TV is not set up. */
        fun openPanel() {
            instance?.let { service -> service.main.post { service.openPanel() } }
        }

        /** Opens the music remote over the app in front, when the mini-remote is switched on. */
        fun openMiniRemote() {
            instance?.let { service -> service.main.post { if (service.settings.tvMiniRemote) service.miniRemote?.open() } }
        }

        /** The service's name for `enabled_accessibility_services`, see [AccessibilitySetup.component]. */
        fun component(packageName: String): String = AccessibilitySetup.component(packageName, MenuKeyService::class.java.name)
    }
}
