package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.besliky.airplaytv.lg.TvMenuMode
import io.github.besliky.airplaytv.ui.NowPlayingActivity

/**
 * Lets the Menu button (the three bars next to Home) open the LG TV's quick settings in every app. Android
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
        overlay = VolumeOverlay(this)
        ReceiverState.observe(volumeListener)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        ReceiverState.remove(volumeListener)
        overlay?.remove()
        overlay = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !VolumeKeys.isVolumeKey(event.keyCode)) UserKeys.listener?.invoke()
        // The volume keys, while the receiver has asked for them (the TV owns the volume); otherwise they go on to the system.
        if (VolumeKeys.onKey(event.keyCode, event.action)) return true
        // Menu opens the TV's menu; while it is open the arrow keys, OK and Back steer it. Every other key is left alone.
        if (event.keyCode != KeyEvent.KEYCODE_MENU && !TvMenuMode.isActive) return false
        return TvMenuMode.onKey(this, event)
    }

    companion object {
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

        /** The service's name for `enabled_accessibility_services`, see [AccessibilitySetup.component]. */
        fun component(packageName: String): String = AccessibilitySetup.component(packageName, MenuKeyService::class.java.name)
    }
}
