package io.github.besliky.airplaytv.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The sound as bars: a window of the music is turned into the strength of each of a few bands of pitch, for the calm visualizer on
 * the Now Playing screen. The window comes from [PcmTap]; this is only the arithmetic, so it can be tested.
 */
object Spectrum {

    /** An in-place fast Fourier transform of [re] and [im]; their length must be a power of two. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0 && im.size == n) { "the length must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                start += length
            }
            length = length shl 1
        }
    }

    /**
     * Fills [out] with the level (0 to 1) of each of its bands for the [samples] (mono, -1 to 1, a power of two of them) played at
     * [sampleRate]: bands spaced like pitch, from [LOW_HZ] to [HIGH_HZ], and each level the loudness of its band in decibels,
     * from [FLOOR_DB] (nothing) to [CEIL_DB] (all there is).
     */
    fun bars(samples: FloatArray, sampleRate: Int, out: FloatArray) {
        val n = samples.size
        val re = FloatArray(n)
        val im = FloatArray(n)
        // a Hann window stops the edges of the window from smearing every band
        for (i in 0 until n) re[i] = samples[i] * (0.5f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat())
        fft(re, im)
        val bands = out.size
        val binHz = sampleRate.toFloat() / n
        for (band in 0 until bands) {
            val from = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(band.toFloat() / bands)
            val to = LOW_HZ * (HIGH_HZ / LOW_HZ).pow((band + 1f) / bands)
            val first = (from / binHz).toInt().coerceIn(1, n / 2 - 1)
            val last = (to / binHz).toInt().coerceIn(first, n / 2 - 1)
            var peak = 0f
            for (bin in first..last) peak = maxOf(peak, hypot(re[bin], im[bin]))
            // a full-scale sine puts n/4 into its bin with this window
            val level = peak / (n / 4f)
            val db = if (level <= 1e-6f) FLOOR_DB else (20f * ln(level) / LN_10).coerceAtLeast(FLOOR_DB)
            out[band] = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0f, 1f)
        }
    }

    private const val LOW_HZ = 60f
    private const val HIGH_HZ = 12_000f
    private const val FLOOR_DB = -66f
    private const val CEIL_DB = -6f
    private val LN_10 = ln(10f)
}

/**
 * A short ring of the last samples of the music, filled by the audio thread and read by the screen. Nothing is copied unless
 * [enabled]: the visualizer turns it on while it is on screen.
 */
class PcmTap(private val capacity: Int = 4096) {

    private val ring = ShortArray(capacity)

    @Volatile
    private var writeCount = 0L

    @Volatile
    var enabled = false

    /** Takes [bytes] bytes of interleaved 16-bit samples from the start of [buffer] (its position is left alone) and keeps them as mono. */
    fun write(buffer: ByteBuffer, bytes: Int, channels: Int) {
        if (!enabled) return
        val frames = bytes / (2 * channels)
        val order = buffer.order()
        buffer.order(ByteOrder.nativeOrder())
        var at = writeCount
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += buffer.getShort((f * channels + c) * 2).toInt()
            ring[(at % capacity).toInt()] = (sum / channels).toShort()
            at++
        }
        buffer.order(order)
        writeCount = at
    }

    /** Copies the last [out].size samples as floats (-1 to 1) into [out]; false when there are not that many yet. */
    fun snapshot(out: FloatArray): Boolean {
        val end = writeCount
        if (end < out.size) return false
        val start = end - out.size
        for (i in out.indices) out[i] = ring[((start + i) % capacity).toInt()] / 32768f
        return true
    }

    fun clear() {
        writeCount = 0
    }
}

/** The one tap of the music, shared by the audio thread (which fills it) and the visualizer (which reads it). */
object AudioTap {
    val tap = PcmTap()
}
