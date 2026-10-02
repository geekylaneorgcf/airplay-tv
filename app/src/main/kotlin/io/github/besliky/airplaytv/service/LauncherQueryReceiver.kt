package io.github.besliky.airplaytv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The home screen app asks whether something is playing; the answer goes to that app only, whoever asked
 * (see [LauncherLink]). The question carries nothing and the answer is a title and an artist at most.
 */
class LauncherQueryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LauncherLink.ACTION_QUERY) LauncherLink.answer(context.applicationContext)
    }
}
