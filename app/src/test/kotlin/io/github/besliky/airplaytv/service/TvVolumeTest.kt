package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvVolumeTest {

    @Test
    fun `the slider moves the TV between zero and the volume it had`() {
        assertEquals(15, TvVolume.target(1f, 15))
        assertEquals(8, TvVolume.target(0.5f, 15))
        assertEquals(5, TvVolume.target(0.33f, 15))
        assertEquals(0, TvVolume.target(0f, 15))
    }

    @Test
    fun `the TV is never taken above the volume it had, whatever the slider says`() {
        assertEquals(15, TvVolume.target(3f, 15))
        assertEquals(0, TvVolume.target(-1f, 15))
        assertEquals(100, TvVolume.target(1f, 250))
    }

    @Test
    fun `a slider that began low leaves the TV where it was`() {
        // a phone that has not played to this receiver before starts its slider a third of the way up
        val home = TvVolume.home(0.33f)
        assertEquals(17, TvVolume.target(TvVolume.fraction(0.33f, home, 1f), 17))
        // turned down to half of where it began: half of the TV's volume
        assertEquals(8, TvVolume.target(TvVolume.fraction(0.165f, home, 1f), 16))
        assertEquals(0, TvVolume.target(TvVolume.fraction(0f, home, 1f), 17))
        // turned up: never above what the TV had
        assertEquals(17, TvVolume.target(TvVolume.fraction(1f, home, 1f), 17))
    }

    @Test
    fun `a slider that began at the top moves the TV as it always did`() {
        val home = TvVolume.home(1f)
        assertEquals(1f, home, 0f)
        assertEquals(15, TvVolume.target(TvVolume.fraction(1f, home, 1f), 15))
        assertEquals(8, TvVolume.target(TvVolume.fraction(0.5f, home, 1f), 15))
        assertEquals(5, TvVolume.target(TvVolume.fraction(0.33f, home, 1f), 15))
    }

    @Test
    fun `the ceiling of the volume limits holds the TV below its volume, from the start`() {
        val home = TvVolume.home(0.4f)
        // a night ceiling of half: the TV begins at half of its volume and the top of the slider is no higher
        assertEquals(10, TvVolume.target(TvVolume.fraction(0.4f, home, 0.5f), 20))
        assertEquals(10, TvVolume.target(TvVolume.fraction(1f, home, 0.5f), 20))
        assertEquals(5, TvVolume.target(TvVolume.fraction(0.2f, home, 0.5f), 20))
    }

    @Test
    fun `a slider that began at the bottom is not taken as home`() {
        assertEquals(0.25f, TvVolume.home(0f), 0f)
        assertEquals(0.25f, TvVolume.home(0.1f), 0f)
        assertEquals(0.6f, TvVolume.home(0.6f), 0f)
        assertEquals(1f, TvVolume.home(7f), 0f)
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

    @Test
    fun `a volume the owner set on the TV moves the top of the slider`() {
        // the slider stood at half, the TV was turned up to 12: full would be 24
        assertEquals(24, TvVolume.referenceAfterTvChange(12, 0.5f))
        assertEquals(15, TvVolume.referenceAfterTvChange(15, 1f))
        // a TV turned right down still leaves something to move
        assertEquals(1, TvVolume.referenceAfterTvChange(0, 0.5f))
        assertEquals(100, TvVolume.referenceAfterTvChange(80, 0.5f))
    }

    @Test
    fun `no reference is guessed from a slider that stood nearly at the bottom`() {
        assertNull(TvVolume.referenceAfterTvChange(5, 0.05f))
        assertNull(TvVolume.referenceAfterTvChange(5, 0f))
    }
}
