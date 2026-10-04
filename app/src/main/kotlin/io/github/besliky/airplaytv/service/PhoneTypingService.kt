package io.github.besliky.airplaytv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.besliky.airplaytv.Settings

/**
 * Type on the phone. This is a second accessibility service, switched on by the owner on purpose (see Extras, Type On Phone): it needs to
 * look at which text box is in focus and to put text into it, which the Menu button service never does. It does nothing while the option
 * is off, looks only for "a text box is in focus", and puts into it only what arrives at [TypingServer] with the code that the TV shows.
 * It works with text boxes that Android knows about; an app that draws its own keyboard and boxes (YouTube) has none to find.
 */
class PhoneTypingService : AccessibilityService() {

    private var banner: TvBanner? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        banner = TvBanner(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        banner?.remove()
        banner = null
        TypingServer.stop()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return
        if (!Settings(this).phoneTyping) {
            TypingServer.stop()
            return
        }
        val node = event.source ?: return
        val editable = node.isEditable
        node.recycle()
        if (!editable) return
        val address = ReceiverState.current.addresses.firstOrNull { it.contains('.') && !it.contains(':') } ?: return
        val code = TypingServer.open()
        banner?.show("Type on your phone: http://$address:${TypingServer.PORT}/?k=$code", amber = false, forMs = 15_000L)
    }

    /** Puts [text] into the text box that is in focus; false when there is none (or the app will not take it). */
    private fun put(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            if (!focused.isEditable) return false
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            focused.recycle()
        }
    }

    companion object {
        @Volatile
        private var instance: PhoneTypingService? = null

        /** True while the owner has switched this service on. */
        val isEnabled: Boolean get() = instance != null

        fun setText(text: String): Boolean = instance?.put(text.take(2000)) ?: false

        /** The service's name for `enabled_accessibility_services`, see [AccessibilitySetup.component]. */
        fun component(packageName: String): String = AccessibilitySetup.component(packageName, PhoneTypingService::class.java.name)
    }
}
