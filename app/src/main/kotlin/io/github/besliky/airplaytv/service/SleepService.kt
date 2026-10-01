package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Lets the app turn the screen off again a few minutes after the music stops, which only an
 * accessibility service is allowed to do on a stock Android TV (Fire OS protects its own sleep action
 * with a signature permission). It does not read the screen and listens to no events; it only exists
 * so that [sleepNow] can ask the system to lock the screen, which on a TV stick means "go to sleep".
 *
 * It is off until the user enables it once from a computer, see the Sleep After Music setting.
 */
class SleepService : AccessibilityService() {

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

    companion object {
        @Volatile
        private var instance: SleepService? = null

        /** True while the user has switched the service on. */
        val isEnabled: Boolean get() = instance != null

        /** Puts the device to sleep: the screen turns off and the TV follows over HDMI-CEC. */
        fun sleepNow(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < 28) return false
            return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
