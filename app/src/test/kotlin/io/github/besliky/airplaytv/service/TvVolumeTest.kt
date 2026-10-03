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
