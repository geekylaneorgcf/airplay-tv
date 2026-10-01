package io.github.besliky.airplaytv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DitherTest {

    private val top = 0xFF372D27.toInt()
    private val bottom = 0xFF1B1613.toInt()

    private fun render(w: Int, h: Int): IntArray = IntArray(w * h).also { Dither.gradient(it, w, h, top, bottom) }

    @Test
    fun `every channel stays within one level of the exact gradient`() {
        val w = 320
        val h = 180
        val out = render(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = out[y * w + x]
                assertTrue(abs((p shr 16 and 0xFF) - Dither.exact(x, y, w, h, 0x37, 0x1B)) <= 1.0001f)
                assertTrue(abs((p shr 8 and 0xFF) - Dither.exact(x, y, w, h, 0x2D, 0x16)) <= 1.0001f)
                assertTrue(abs((p and 0xFF) - Dither.exact(x, y, w, h, 0x27, 0x13)) <= 1.0001f)
                assertEquals(0xFF, p ushr 24)
            }
        }
    }

    @Test
    fun `block averages follow the exact gradient to a fraction of a level`() {
        // Plain rounding is off by up to half a level somewhere in every band; dithering keeps the local
        // average honest, which is what turns a band edge into grain.
        val w = 640
        val h = 360
        val out = render(w, h)
        var worst = 0f
        for (by in 0 until h step 16) {
            for (bx in 0 until w step 16) {
                var error = 0f
                for (y in by until by + 16) {
                    for (x in bx until bx + 16) {
                        error += (out[y * w + x] shr 16 and 0xFF) - Dither.exact(x, y, w, h, 0x37, 0x1B)
                    }
                }
                worst = maxOf(worst, abs(error / 256f))
            }
        }
        assertTrue("worst block error $worst", worst < 0.15f)
    }

    @Test
    fun `rows have no long runs of identical pixels`() {
        // An undithered gradient of this size has runs of 60 to 90 pixels, which are the bands.
        val w = 1920
        val h = 1080
        val out = render(w, h)
        for (y in intArrayOf(60, 540, 1010)) {
            var longest = 1
            var run = 1
            for (x in 1 until w) {
                if (out[y * w + x] == out[y * w + x - 1]) run++ else run = 1
                longest = maxOf(longest, run)
            }
            assertTrue("row $y has a run of $longest", longest < 16)
        }
    }

    @Test
    fun `black and flat colours stay as they are`() {
        val black = IntArray(64 * 36).also { Dither.gradient(it, 64, 36, 0xFF000000.toInt(), 0xFF000000.toInt()) }
        assertTrue(black.all { it == 0xFF000000.toInt() })
        val flat = IntArray(64 * 36).also { Dither.gradient(it, 64, 36, 0xFF402010.toInt(), 0xFF402010.toInt()) }
        assertTrue(flat.all { it == 0xFF402010.toInt() })
    }

    @Test
    fun `the pattern is the same every time`() {
        assertTrue(render(96, 54).contentEquals(render(96, 54)))
    }

    @Test
    fun `noise stays in range and is spread evenly`() {
        var sum = 0.0
        val n = 100 * 100
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val v = Dither.noise(x, y)
                assertTrue(v >= 0f && v < 1f)
                sum += v
            }
        }
        assertEquals(0.5, sum / n, 0.03)
    }
}
