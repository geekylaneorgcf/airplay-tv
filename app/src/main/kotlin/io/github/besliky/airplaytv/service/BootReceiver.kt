package io.github.besliky.airplaytv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Settings

/** Brings the receiver back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val settings = Settings(context)
        if (settings.enabled && settings.startAutomatically) {
            Log.i(Log.Category.SERVICE, "starting after $action")
            ReceiverService.start(context)
        }
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
