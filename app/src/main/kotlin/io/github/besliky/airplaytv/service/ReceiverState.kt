package io.github.besliky.airplaytv.service

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Observable state of the receiver, shared between the service and the UI (main thread). */
object ReceiverState {

    enum class Status { OFF, STARTING, READY, NO_NETWORK, ERROR, CONNECTED }

    data class Snapshot(
        val status: Status = Status.OFF,
        val deviceName: String = "",
        val publishedName: String = "",
        val addresses: List<String> = emptyList(),
        val transport: String = "",
        val port: Int = 0,
        val clientName: String? = null,
        val clientModel: String? = null,
        val videoActive: Boolean = false,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val audioActive: Boolean = false,
        val pin: String? = null,
        val error: String? = null,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artwork: Bitmap? = null,
        /** Track length and position at [positionAtMs] (SystemClock.elapsedRealtime), or -1 if unknown. */
        val durationMs: Long = -1,
        val positionMs: Long = -1,
        val positionAtMs: Long = 0,
        /** Counts the sender's own progress messages (not the pause and play changes, which also restamp the position). */
        val progressSeq: Int = 0,
        /** False between a pause and the next audio from the sender. */
        val playing: Boolean = true,
        /** The sender's volume slider position, 0..1 (AirPlay maps it to -30..0 dB), or -1 if unknown. */
        val volume: Float = -1f,
        val volumeAtMs: Long = 0,
        /** Counts track changes; [trackDirection] is 1 for a next track, -1 when the previous one came back. */
        val trackSeq: Int = 0,
        val trackDirection: Int = 1,
        /** Counts artwork messages, so a new cover is noticed even when it decodes to an equal picture. */
        val artworkSeq: Int = 0,
        val artColors: ArtworkColors? = null,
        val lyricsState: LyricsState = LyricsState.OFF,
        val lyrics: Lyrics? = null,
        /** AirPlay turned the screen on for this session and nobody has touched the remote since. */
        val wokeDevice: Boolean = false,
        /** The photo being shown from the sender's Photos app, and a counter that changes with every photo. */
        val photo: Bitmap? = null,
        val photoSeq: Int = 0,
    )

    /**
     * OFF: the feature is off. LOADING: a lookup is running. FOUND/NOT_FOUND: its result.
     * UNAVAILABLE: the lyrics service could not be reached, as opposed to having no lyrics for the song.
     */
    enum class LyricsState { OFF, LOADING, FOUND, NOT_FOUND, UNAVAILABLE }

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()

    @Volatile
    var current = Snapshot()
        private set

    fun update(transform: (Snapshot) -> Snapshot) {
        val next = transform(current)
        if (next == current) return
        current = next
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it(next) }
        } else {
            handler.post { listeners.forEach { it(current) } }
        }
    }

    fun observe(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun remove(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }
}
