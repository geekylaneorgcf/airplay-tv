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

    @Volatile
    private var running = false

    @Volatile
    private var gain = 1f
    private var trim = 1f

    @Volatile
    var bufferMs: Int = 0
        private set

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
        t.setVolume(gain * trim)
        bufferMs = t.bufferSizeInFrames * 1000 / sampleRate
        track = t
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
        track?.setVolume(gain * trim)
    }

    /** The receiver's own volume, applied on top of the sender's. */
    fun setTrim(value: Float) {
        trim = value.coerceIn(0f, 1f)
        track?.setVolume(gain * trim)
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            it.join(1000)
        }
        thread = null
        track?.release()
        track = null
        abandonFocus()
    }

    private fun requestFocus(attributes: AudioAttributes) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
