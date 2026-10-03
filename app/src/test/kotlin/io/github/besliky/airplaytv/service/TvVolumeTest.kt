package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvVolumeTest {

    @Test
    fun `the slider is the TV's real scale`() {
        assertEquals(100, TvVolume.absolute(1f, 100))
        assertEquals(70, TvVolume.absolute(0.7f, 100))
        assertEquals(30, TvVolume.absolute(0.3f, 100))
        assertEquals(0, TvVolume.absolute(0f, 100))
        assertEquals(100, TvVolume.absolute(3f, 100))
        assertEquals(0, TvVolume.absolute(-1f, 100))
    }

    @Test
    fun `a ceiling scales the whole slider into less of the TV's scale`() {
        // a night ceiling of 40 %: the top of the slider is a TV volume of 40, half of it 20
        assertEquals(40, TvVolume.absolute(1f, 40))
        assertEquals(20, TvVolume.absolute(0.5f, 40))
        // the lowest ceiling the limits allow is 10
        assertEquals(10, TvVolume.absolute(1f, 3))
    }

    @Test
    fun `the TV stays where it was until the slider moves, and never above the ceiling`() {
        assertEquals(30, TvVolume.atHome(30, 100))
        assertEquals(25, TvVolume.atHome(30, 25))
        assertEquals(100, TvVolume.atHome(250, 100))
        assertEquals(0, TvVolume.atHome(-5, 100))
    }

    @Test
    fun `a slider counts as moved by more than a hair's breadth`() {
        assertFalse(TvVolume.moved(0.33f, 0.33f))
        assertFalse(TvVolume.moved(0.34f, 0.33f))
        assertTrue(TvVolume.moved(0.5f, 0.33f))
        assertTrue(TvVolume.moved(0.1f, 0.33f))
        // a session whose first level is not known yet has moved nothing to compare with
        assertTrue(TvVolume.moved(0.5f, -1f))
    }

    @Test
    fun `the slider position for a TV volume is the inverse of the scale`() {
        assertEquals(0.3f, TvVolume.levelFor(30, 100), 0.0001f)
        assertEquals(0.5f, TvVolume.levelFor(20, 40), 0.0001f)
        assertEquals(1f, TvVolume.levelFor(90, 40), 0f)
        assertEquals(0f, TvVolume.levelFor(-3, 100), 0f)
        // and stepping one TV unit up from it gives the next TV volume
        assertEquals(31, TvVolume.absolute(TvVolume.levelFor(30, 100) + 1f / 100, 100))
        assertEquals(21, TvVolume.absolute(TvVolume.levelFor(20, 40) + 1f / 40, 40))
    }

    @Test
    fun `a TV left where a session put it goes back to what it had`() {
        // the session put the TV at 6 (from 17) and never gave it back: the TV still stands at 6
        assertEquals(17, TvVolume.leftBehind("6,17", 6))
        // the owner set it since: not ours to move
        assertNull(TvVolume.leftBehind("6,17", 7))
        assertNull(TvVolume.leftBehind("6,17", 17))
        // nothing owed, or a record that is not one
        assertNull(TvVolume.leftBehind("", 6))
        assertNull(TvVolume.leftBehind("17,17", 17))
        assertNull(TvVolume.leftBehind("6", 6))
        assertNull(TvVolume.leftBehind("six,17", 6))
        assertNull(TvVolume.leftBehind("6,300", 6))
        // a session that took the TV all the way down (zero) is owed it back too
        assertEquals(15, TvVolume.leftBehind("0,15", 0))
    }
}
