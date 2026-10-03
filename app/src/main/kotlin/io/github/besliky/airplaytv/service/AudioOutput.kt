package io.github.besliky.airplaytv.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.AUDIO
import io.github.besliky.airplaytv.core.NativeBridge
import java.nio.ByteBuffer

/**
 * Plays the decoded PCM of the current session through an [AudioTrack].
 *
 * A dedicated thread pulls blocks from the native pipeline and writes them with
 * WRITE_BLOCKING, so the AudioTrack buffer paces the pipeline. Mirroring audio uses a
 * small low-latency buffer, music a larger one that rides out Wi-Fi hiccups.
 */
class AudioOutput(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusAttributes: AudioAttributes? = null

    /** Another app took the sound for good: this output is silent until [regainFocus]. */
    @Volatile
    var focusLost = false
        private set

    // silent while another app has the sound (for a moment or for good), and quieter while it speaks over it
    @Volatile
    private var focusMuted = false

    @Volatile
    private var ducked = false

    @Volatile
    private var running = false

    @Volatile
    private var gain = 1f

    @Volatile
    var bufferMs: Int = 0
        private set

    @Volatile
    private var channelCount = 2

    /** Called (on the thread that started the output) with the audio session of a new track, so effects can be put on it. */
    var onTrack: ((Int) -> Unit)? = null

    /** Called when the track is let go. */
    var onStopped: (() -> Unit)? = null

    /** Called on the main thread when another app takes the sound or gives it back: an [AudioManager] AUDIOFOCUS_ constant. */
    var onFocus: ((Int) -> Unit)? = null

    val active: Boolean get() = running

    fun start(sampleRate: Int, channels: Int, lowLatency: Boolean) {
        stop()
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val frameBytes = 2 * (if (channels == 1) 1 else 2)
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(frameBytes * 256)
        val bufferBytes = if (lowLatency) {
            (minBuffer * 2).coerceAtMost(sampleRate * frameBytes * 80 / 1000).coerceAtLeast(minBuffer)
        } else {
            maxOf(minBuffer * 4, sampleRate * frameBytes / 4)
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(
                    if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE
                )
                .build()
        } catch (e: Exception) {
            Log.e(AUDIO, "cannot create AudioTrack", e)
            return
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(AUDIO, "AudioTrack not initialized")
            t.release()
            return
        }
        focusMuted = false
        ducked = false
        focusLost = false
        t.setVolume(gain)
        bufferMs = t.bufferSizeInFrames * 1000 / sampleRate
        channelCount = if (channels == 1) 1 else 2
        track = t
        try {
            onTrack?.invoke(t.audioSessionId)
        } catch (e: RuntimeException) {
            Log.w(AUDIO, "cannot put effects on the track", e)
        }
        requestFocus(attributes)
        running = true
        thread = Thread({ writerLoop(t) }, "AirPlayAudioOut").apply { start() }
        Log.i(AUDIO, "audio output ${sampleRate}Hz, buffer ${bufferMs}ms${if (lowLatency) ", low latency" else ""}")
    }

    private fun writerLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteBuffer.allocateDirect(8192)
        var playing = false
        try {
            while (running) {
                buffer.clear()
                val n = NativeBridge.nativeReadAudio(buffer, buffer.capacity(), 50)
                if (n < 0) break
                if (n == 0) continue
                if (!playing) {
                    t.play()
                    playing = true
                }
                AudioTap.tap.write(buffer, n, channelCount)
                buffer.limit(n)
                while (running && buffer.hasRemaining()) {
                    val written = t.write(buffer, buffer.remaining(), AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.w(AUDIO, "AudioTrack write failed ($written)")
                        running = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(AUDIO, "audio output failed", e)
        }
        try {
            t.pause()
            t.flush()
        } catch (_: IllegalStateException) {
        }
    }

    fun setVolume(value: Float) {
        gain = value.coerceIn(0f, 1f)
        applyTrackVolume()
    }

    private fun applyTrackVolume() {
        track?.setVolume(if (focusMuted) 0f else if (ducked) gain * DUCKED_GAIN else gain)
    }

    /**
     * Takes the sound back from another app, because the sender plays again after the receiver had given it up (the owner pressed
     * play on the phone): that is a choice for AirPlay, and the receiver does not hold the sound against a player the owner started
     * meanwhile any other way, so it is called only for a sender that resumes.
     */
    fun regainFocus() {
        if (!focusLost) return
        focusAttributes?.let { requestFocus(it) }
        focusLost = false
        focusMuted = false
        ducked = false
        applyTrackVolume()
        Log.i(AUDIO, "the sound is taken back from the other app")
    }

    fun stop() {
        // silent at once: what is already in the buffers and on its way to the speakers is not heard any louder than it is now, whatever
        // happens to the TV's volume next (see LgLink.sessionEnded)
        track?.setVolume(0f)
        running = false
        thread?.let {
            it.interrupt()
            it.join(1000)
        }
        thread = null
        if (track != null) onStopped?.invoke()
        track?.release()
        track = null
        abandonFocus()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(AUDIO, "another app took the sound for good")
                focusLost = true
                focusMuted = true
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusMuted = true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> ducked = true
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusMuted = false
                ducked = false
                focusLost = false
            }
        }
        applyTrackVolume()
        onFocus?.invoke(change)
    }

    private fun requestFocus(attributes: AudioAttributes) {
        focusAttributes = attributes
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        focusLost = false
        focusMuted = false
        ducked = false
    }

    private companion object {
        /** How loud the output stays while another app speaks over it (a voice prompt). */
        const val DUCKED_GAIN = 0.25f
    }
}
