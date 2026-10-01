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
        /** False between a pause and the next audio from the sender. */
        val playing: Boolean = true,
        /** The sender's volume slider position, 0..1 (AirPlay maps it to -30..0 dB), or -1 if unknown. */
        val volume: Float = -1f,
        val volumeAtMs: Long = 0,
        /** The receiver's own volume set with the TV remote, slider position 0..1 (-30..0 dB). */
        val outputLevel: Float = 1f,
    )

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
