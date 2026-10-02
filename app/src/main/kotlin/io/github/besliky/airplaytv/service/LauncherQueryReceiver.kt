package io.github.besliky.airplaytv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The home screen app asks whether something is playing; the answer goes to that app only, whoever asked (see [LauncherLink]).
 * The question carries nothing and the answer is a title, an artist and a small cover at most.
 *
 * The home screen's player also asks for play, pause, next and previous. Any app can send such a broadcast, so it only does what
 * the remote's media keys do anyway, only while a sender is playing, and nothing it carries is trusted beyond a place in the song.
 */
class LauncherQueryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LauncherLink.ACTION_QUERY -> LauncherLink.answer(context.applicationContext)
            LauncherLink.ACTION_COMMAND -> {
                if (ReceiverState.current.status != ReceiverState.Status.CONNECTED) return
                val command = LauncherLink.dacpFor(intent.getStringExtra(LauncherLink.EXTRA_COMMAND), intent.getLongExtra(LauncherLink.EXTRA_SEEK_TO, -1L))
                if (command != null) RemoteControl.send(command)
            }
        }
    }
}
