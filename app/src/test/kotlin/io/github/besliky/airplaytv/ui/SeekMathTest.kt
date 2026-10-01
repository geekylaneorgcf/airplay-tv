package io.github.besliky.airplaytv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekMathTest {

    private val song = 240_000L

    @Test
    fun `a tap moves ten seconds`() {
        assertEquals(SeekMath.TAP_MS, SeekMath.step(repeatCount = 0, heldMs = 0, sinceLastEventMs = 0))
        assertEquals(10_000L, SeekMath.TAP_MS)
    }

    @Test
    fun `a held key moves faster the longer it is held`() {
        val slow = SeekMath.step(repeatCount = 3, heldMs = 700, sinceLastEventMs = 100)
        val faster = SeekMath.step(repeatCount = 30, heldMs = 2_000, sinceLastEventMs = 100)
        val fastest = SeekMath.step(repeatCount = 80, heldMs = 5_000, sinceLastEventMs = 100)
        assertEquals(2_000L, slow)
        assertEquals(4_000L, faster)
        assertEquals(8_000L, fastest)
    }

    @Test
    fun `the speed does not depend on how often the remote repeats the key`() {
        val oftenFor1s = (1..20).sumOf { SeekMath.step(it, 700, 50) }
        val rarelyFor1s = (1..10).sumOf { SeekMath.step(it, 700, 100) }
        assertEquals(oftenFor1s, rarelyFor1s)
    }

    @Test
    fun `a long gap between events cannot make a huge jump`() {
        assertEquals(SeekMath.step(5, 700, 200), SeekMath.step(5, 700, 60_000))
        assertEquals(0L, SeekMath.step(5, 700, -50))
    }

    @Test
    fun `the target stays inside the song`() {
        assertEquals(0L, SeekMath.target(4_000, -1, 10_000, song))
        assertEquals(song - SeekMath.END_MARGIN_MS, SeekMath.target(235_000, 1, 10_000, song))
        assertEquals(100_000L, SeekMath.target(90_000, 1, 10_000, song))
        assertEquals(80_000L, SeekMath.target(90_000, -1, 10_000, song))
    }

    @Test
    fun `a song shorter than the margin still gives a target`() {
        assertEquals(0L, SeekMath.target(0, 1, 10_000, 1_000))
    }

    @Test
    fun `the sender's position counts as arrived when it is close to the target`() {
        assertTrue(SeekMath.arrived(100_000, 100_000))
        assertTrue(SeekMath.arrived(103_500, 100_000))
        assertTrue(SeekMath.arrived(96_500, 100_000))
        assertFalse(SeekMath.arrived(110_000, 100_000))
        assertFalse(SeekMath.arrived(30_000, 100_000))
    }
}
