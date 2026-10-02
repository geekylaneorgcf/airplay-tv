package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.besliky.airplaytv.lg.TvMenuMode

/**
 * Lets the Menu button (the three bars next to Home) open the LG TV's quick settings in every app. Android
 * only lets an accessibility service see a key before the app in front of it does, so the button has to
 * be taken here; with the TV Settings Button off the service lets every key through untouched.
 *
 * It looks at the Menu key and nothing else, reads no screen content and listens to no events. It is off
 * until the owner switches it on once from a computer, see the TV Settings Button setting. It is also what
 * puts the stick to sleep for Sleep After Music (see [SleepService]), so one switch does both.
 */
class MenuKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
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
