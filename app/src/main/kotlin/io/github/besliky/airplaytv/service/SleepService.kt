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
 * The Menu button service can do the same, so one switch serves both: whichever of the two is on is used,
 * and the setup this app shows is the Menu button service's. This one stays for anyone who already enabled it.
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

        /** True while the user has switched this service or the Menu button service on. */
        val isEnabled: Boolean get() = instance != null || MenuKeyService.isEnabled

        /**
         * The service's name as `enabled_accessibility_services` takes it, in the short form Android
         * accepts (`package/.service.SleepService`), which is half the length of the full one to type.
         */
        fun component(packageName: String, className: String = SleepService::class.java.name): String =
            AccessibilitySetup.component(packageName, className)

        /** Puts the device to sleep: the screen turns off and the TV follows over HDMI-CEC. */
        fun sleepNow(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            val service = instance ?: return MenuKeyService.sleepNow()
            return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
