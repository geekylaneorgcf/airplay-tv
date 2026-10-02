package io.github.besliky.airplaytv.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs

/**
 * Tells the home screen app (tvhome) what is being played over AirPlay, so that its status strip can show a chip that takes you back
 * to the player and, with a press, a small player of its own. It is one-way and small: a broadcast addressed to that app alone, with
 * the kind of session, the title and artist, whether the music plays or is paused, who plays it, how long the song is and where it
 * is, and a small picture of the cover. The home screen asks for the current state with [ACTION_QUERY] when it comes to the front,
 * because it may not have been running when the state last changed. The other way, the home screen's player asks for play, pause,
 * next and previous with [ACTION_COMMAND] (see [LauncherCommandReceiver]).
 */
object LauncherLink {

    const val ACTION_STATE = "io.github.besliky.airplaytv.action.NOW_PLAYING_STATE"
    const val ACTION_QUERY = "io.github.besliky.airplaytv.action.QUERY_NOW_PLAYING"
    const val ACTION_COMMAND = "io.github.besliky.airplaytv.action.COMMAND"
    const val LAUNCHER_PACKAGE = "io.github.geekylaneorgcf.tvhome"

    const val EXTRA_KIND = "kind"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_PLAYING = "playing"

    /** Who plays (the phone's name), the length and place of the song in milliseconds (the place was true at [EXTRA_POSITION_AT], a time of SystemClock.elapsedRealtime), and a small JPEG of the cover. */
    const val EXTRA_CLIENT = "client"
    const val EXTRA_DURATION = "duration"
    const val EXTRA_POSITION = "position"
    const val EXTRA_POSITION_AT = "positionAt"
    const val EXTRA_COVER = "cover"

    const val EXTRA_COMMAND = "command"
    const val EXTRA_SEEK_TO = "seekTo"

    /** The cover goes as a picture this many pixels on a side. */
    private const val COVER_PX = 192
    private const val COVER_QUALITY = 82

    /** A position that differs from where the last one was heading by more than this is a seek or a skip, and is announced. */
    private const val JUMP_MS = 2500L

    enum class Kind(val wire: String) { NONE("none"), AUDIO("audio"), VIDEO("video") }

    /**
     * [playing] is false while the music is paused (the session goes on, the chip should say so); it means nothing for video.
     * [coverSeq] changes when a new cover arrives, so the picture is sent again only then. The place in the song is not part of
     * the state: it changes all the time and is sent with a change of the rest, or when it jumps.
     */
    data class State(
        val kind: Kind,
        val title: String,
        val artist: String,
        val playing: Boolean = true,
        val client: String = "",
        val coverSeq: Int = 0,
        val durationMs: Long = -1,
    )

    /** What the home screen should show for this snapshot of the receiver. */
    fun stateOf(s: ReceiverState.Snapshot): State {
        if (s.status != ReceiverState.Status.CONNECTED) return State(Kind.NONE, "", "")
        return when {
            s.videoActive || s.urlVideo -> State(Kind.VIDEO, "", "")
            s.audioActive -> State(Kind.AUDIO, s.title, s.artist, s.playing, s.clientName.orEmpty(), s.artworkSeq, s.durationMs)
            else -> State(Kind.NONE, "", "")
        }
    }

    /** Where the song is at [nowMs] (a time of SystemClock.elapsedRealtime), or -1 when that is not known. */
    fun positionNow(s: ReceiverState.Snapshot, nowMs: Long): Long {
        if (s.durationMs <= 0 || s.positionMs < 0) return -1
        var position = s.positionMs
        if (s.audioActive && s.playing) position += nowMs - s.positionAtMs
        return position.coerceIn(0L, s.durationMs)
    }

    /**
     * Whether [next] has to be announced: when it differs from what was announced last, or when the song's place is not where the
     * last announcement said it was heading (a seek). [lastPosition] was true at [lastAt]; [position] is true at [now].
     */
    fun shouldSend(previous: State?, next: State, lastPosition: Long, lastAt: Long, position: Long, now: Long): Boolean {
        if (previous == null || previous != next) return true
        if (next.kind != Kind.AUDIO || lastPosition < 0 || position < 0) return false
        val expected = lastPosition + if (next.playing) now - lastAt else 0L
        return abs(position - expected) > JUMP_MS
    }

    /** The command of the remote protocol an [ACTION_COMMAND] asks for, or null when it asks for nothing that is known. */
    fun dacpFor(command: String?, seekToMs: Long): String? = when (command) {
        "playpause" -> DacpClient.PLAY_PAUSE
        "next" -> DacpClient.NEXT
        "previous" -> DacpClient.PREVIOUS
        "seek" -> if (seekToMs >= 0) DacpClient.seekTo(seekToMs) else null
        else -> null
    }

    private var last: State? = null
    private var lastPosition = -1L
    private var lastPositionAt = 0L
    private var cover: ByteArray? = null
    private var coverOf = -1

    /** Sends the state when it differs from the one last sent. Called on every change of the receiver's state. */
    fun onState(context: Context, snapshot: ReceiverState.Snapshot) {
        val next = stateOf(snapshot)
        val now = SystemClock.elapsedRealtime()
        val position = positionNow(snapshot, now)
        if (!shouldSend(last, next, lastPosition, lastPositionAt, position, now)) return
        send(context, next, snapshot, position, now)
    }

    /** Answers a query: the current state, whether or not it changed. */
    fun answer(context: Context) {
        val snapshot = ReceiverState.current
        val now = SystemClock.elapsedRealtime()
        send(context, stateOf(snapshot), snapshot, positionNow(snapshot, now), now)
    }

    private fun send(context: Context, state: State, snapshot: ReceiverState.Snapshot, position: Long, now: Long) {
        last = state
        lastPosition = position
        lastPositionAt = now
        val intent = Intent(ACTION_STATE)
            .setPackage(LAUNCHER_PACKAGE)
            .putExtra(EXTRA_KIND, state.kind.wire)
            .putExtra(EXTRA_TITLE, state.title)
            .putExtra(EXTRA_ARTIST, state.artist)
            .putExtra(EXTRA_PLAYING, state.playing)
        if (state.kind == Kind.AUDIO) {
            intent.putExtra(EXTRA_CLIENT, state.client).putExtra(EXTRA_DURATION, state.durationMs)
            if (position >= 0) intent.putExtra(EXTRA_POSITION, position).putExtra(EXTRA_POSITION_AT, now)
            coverFor(snapshot)?.let { intent.putExtra(EXTRA_COVER, it) }
        }
        try {
            context.sendBroadcast(intent)
        } catch (_: RuntimeException) {
            // nothing to tell, or no one to tell it to
        }
    }

    /** The small picture of the cover; made when a new cover came, and kept until the next. */
    private fun coverFor(snapshot: ReceiverState.Snapshot): ByteArray? {
        val artwork = snapshot.artwork ?: return null
        if (snapshot.artworkSeq != coverOf || cover == null) {
            cover = CoverThumb.jpeg(artwork, COVER_PX, COVER_QUALITY)
            coverOf = snapshot.artworkSeq
        }
        return cover
    }
}

/** A cover as a small square JPEG, for the broadcast to the home screen. */
object CoverThumb {
    fun jpeg(source: Bitmap, sizePx: Int, quality: Int): ByteArray? = try {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== square && scaled !== source) scaled.recycle()
        if (square !== source) square.recycle()
        out.toByteArray()
    } catch (_: RuntimeException) {
        null
    }
}
