package io.github.besliky.airplaytv.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumTest {

    private fun sine(hz: Int, n: Int = 1024, rate: Int = 44100, amplitude: Float = 0.8f) =
        FloatArray(n) { (amplitude * sin(2.0 * PI * hz * it / rate)).toFloat() }

    @Test
    fun `a transform finds the one frequency of a sine`() {
        val n = 256
        val re = FloatArray(n) { sin(2.0 * PI * 8 * it / n).toFloat() }
        val im = FloatArray(n)
        Spectrum.fft(re, im)
        val magnitudes = FloatArray(n / 2) { kotlin.math.hypot(re[it], im[it]) }
        val peak = magnitudes.indices.maxByOrNull { magnitudes[it] }
        assertEquals(8, peak)
        assertEquals(n / 2f, magnitudes[8], 0.5f)
    }

    @Test
    fun `silence is no bars at all`() {
        val out = FloatArray(24)
        Spectrum.bars(FloatArray(1024), 44100, out)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `a tone lights the band that holds its pitch more than the bands far from it`() {
        val out = FloatArray(24)
        Spectrum.bars(sine(1000), 44100, out)
        val loudest = out.indices.maxByOrNull { out[it] }!!
        // 1 kHz is about halfway up the log scale from 60 Hz to 12 kHz
        assertTrue("band $loudest", loudest in 10..14)
        assertTrue(out[loudest] > 0.6f)
        assertTrue(out[2] < 0.3f)
        assertTrue(out[22] < 0.3f)
        assertTrue(out.all { it in 0f..1f })
    }

    @Test
    fun `a low tone is in the low bands and a high tone in the high ones`() {
        val low = FloatArray(24)
        val high = FloatArray(24)
        Spectrum.bars(sine(100), 44100, low)
        Spectrum.bars(sine(8000), 44100, high)
        assertTrue(low.indices.maxByOrNull { low[it] }!! < 6)
        assertTrue(high.indices.maxByOrNull { high[it] }!! > 17)
    }

    @Test
    fun `the tap keeps the last samples as mono, only while it is on`() {
        val tap = PcmTap(capacity = 8)
        val buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        // four stereo frames: left 1000 and right 3000 each
        repeat(4) {
            buffer.putShort(1000)
            buffer.putShort(3000)
        }
        tap.write(buffer, 16, 2)
        val out = FloatArray(2)
        assertFalse("off: nothing was kept", tap.snapshot(out))
        tap.enabled = true
        tap.write(buffer, 16, 2)
        assertTrue(tap.snapshot(out))
        assertEquals(2000f / 32768f, out[0], 0.0001f)
        assertEquals(2000f / 32768f, out[1], 0.0001f)
        assertFalse("more than were written", tap.snapshot(FloatArray(5)))
    }
}
