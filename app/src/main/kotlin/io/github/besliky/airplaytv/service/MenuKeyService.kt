package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
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
 * It looks at the Menu key (and, while the receiver asks for them, the volume keys: see [VolumeKeys]) and nothing else, reads no screen
 * content and listens to no events. While AirPlay plays and the volume is moved, it also draws a small volume bar (see
 * [VolumeOverlay]) over any screen but the player's own. It is off
 * until the owner switches it on once from a computer, see the TV Settings Button setting. It is also what
 * puts the stick to sleep for Sleep After Music (see [SleepService]), so one switch does both.
 */
class MenuKeyService : AccessibilityService() {

    private var overlay: VolumeOverlay? = null
    private var panel: TvPanel? = null
    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = Settings(this)
        overlay = VolumeOverlay(this)
        panel = TvPanel(this)
        TvNotices.attach(this, TvBanner(this))
        TvActions.appContext = applicationContext
        TvSleepTimer.resume(this)
        ReceiverState.observe(volumeListener)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        ReceiverState.remove(volumeListener)
        overlay?.remove()
        overlay = null
        panel?.close()
        panel = null
        TvNotices.detach()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (!VolumeKeys.isVolumeKey(event.keyCode)) UserKeys.listener?.invoke()
            TvActions.onAnyKey()
            TvSleepTimer.onKey(this)
        }
        // The volume keys, while the receiver has asked for them (the TV owns the volume); otherwise they go on to the system.
        if (VolumeKeys.onKey(event.keyCode, event.action)) return true
        // Menu: a press opens the TV's own menu, a double press the quick panel, a hold what the owner chose (see MenuGesture).
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (!settings.tvMenuButton) return false
            if (event.action == KeyEvent.ACTION_DOWN) gesture.onDown(event.repeatCount) else if (event.action == KeyEvent.ACTION_UP) gesture.onUp()
            return true // the press and its release belong to the TV now
        }
        // While the panel is open it takes the arrow keys, OK and Back; while the TV's menu is open the same keys steer that.
        if (panel?.isOpen == true) return panel?.onKey(event) == true
        if (!TvMenuMode.isActive) return false
        return TvMenuMode.onKey(this, event)
    }

    /** A press of Menu while the panel or the TV's menu is open closes it (and does nothing else). */
    private fun closeOpen(): Boolean {
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
            return service.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        /** The service's name for `enabled_accessibility_services`, see [AccessibilitySetup.component]. */
        fun component(packageName: String): String = AccessibilitySetup.component(packageName, MenuKeyService::class.java.name)
    }
}
