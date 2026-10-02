package io.github.besliky.airplaytv.service

import android.content.Context
import android.content.Intent

/**
 * Tells the home screen app (tvhome) whether something is being played over AirPlay, so that its status strip can
 * show an "AirPlay" chip that takes you back to the player. It is one-way and small: a broadcast addressed to that app
 * alone, with the kind of session, the title and artist, and whether the music plays or is paused. The home screen asks for the current state with
 * [ACTION_QUERY] when it comes to the front, because it may not have been running when the state last changed.
 */
object LauncherLink {

    const val ACTION_STATE = "io.github.besliky.airplaytv.action.NOW_PLAYING_STATE"
    const val ACTION_QUERY = "io.github.besliky.airplaytv.action.QUERY_NOW_PLAYING"
    const val LAUNCHER_PACKAGE = "io.github.geekylaneorgcf.tvhome"

    const val EXTRA_KIND = "kind"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_PLAYING = "playing"

    enum class Kind(val wire: String) { NONE("none"), AUDIO("audio"), VIDEO("video") }

    /** [playing] is false while the music is paused (the session goes on, the chip should say so); it means nothing for video. */
    data class State(val kind: Kind, val title: String, val artist: String, val playing: Boolean = true)

    /** What the home screen should show for this snapshot of the receiver. */
    fun stateOf(s: ReceiverState.Snapshot): State {
        if (s.status != ReceiverState.Status.CONNECTED) return State(Kind.NONE, "", "")
        return when {
            s.videoActive -> State(Kind.VIDEO, "", "")
            s.audioActive -> State(Kind.AUDIO, s.title, s.artist, s.playing)
            else -> State(Kind.NONE, "", "")
        }
    }

    private var last: State? = null

    /** Sends the state when it differs from the one last sent. Called on every change of the receiver's state. */
    fun onState(context: Context, snapshot: ReceiverState.Snapshot) {
        val next = stateOf(snapshot)
        if (next == last) return
        last = next
        send(context, next)
    }

    /** Answers a query: the current state, whether or not it changed. */
    fun answer(context: Context) {
        val state = stateOf(ReceiverState.current)
        last = state
        send(context, state)
    }

    private fun send(context: Context, state: State) {
        val intent = Intent(ACTION_STATE)
            .setPackage(LAUNCHER_PACKAGE)
            .putExtra(EXTRA_KIND, state.kind.wire)
            .putExtra(EXTRA_TITLE, state.title)
            .putExtra(EXTRA_ARTIST, state.artist)
            .putExtra(EXTRA_PLAYING, state.playing)
        try {
            context.sendBroadcast(intent)
        } catch (_: RuntimeException) {
            // nothing to tell, or no one to tell it to
        }
    }
}
