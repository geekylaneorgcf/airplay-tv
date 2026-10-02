package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.core.NativeBridge
import io.github.besliky.airplaytv.service.ReceiverState

/**
 * A second phone asked to play while a first one is: the owner decides, with the remote, whether it may. "Let it play" ends the
 * session that is playing (the other phone then tries again, which now succeeds); the answer to anything else, or no answer, keeps
 * what plays. It closes by itself after a while, so a question nobody sees does not sit on the screen.
 */
class TakeoverActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val newcomer = intent.getStringExtra(EXTRA_NAME).orEmpty().ifEmpty { getString(R.string.takeover_somebody) }
        val current = ReceiverState.current.clientName ?: getString(R.string.takeover_somebody)
        Dialogs.prompt(
            this,
            getString(R.string.takeover_title, newcomer),
            getString(R.string.takeover_message, current, newcomer),
            getString(R.string.takeover_let),
            getString(R.string.takeover_keep),
            onConfirm = { if (NativeBridge.loaded) NativeBridge.nativeDisconnect() },
            onDismiss = { finish() },
        )
        handler.postDelayed({ if (!isFinishing) finish() }, ANSWER_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAME = "name"
        private const val ANSWER_MS = 30_000L
    }
}
