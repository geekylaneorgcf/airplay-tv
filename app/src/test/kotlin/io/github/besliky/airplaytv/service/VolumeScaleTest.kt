package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class VolumeScaleTest {

    private fun gainOfDb(db: Float) = Math.pow(10.0, db / 20.0).toFloat()

    @Test
    fun `the ends of the slider are the ends of the scale`() {
        assertEquals(1f, VolumeScale.gainToLevel(1f), 1e-4f)
        assertEquals(0f, VolumeScale.gainToLevel(gainOfDb(-30f)), 1e-4f)
        assertEquals(0f, VolumeScale.gainToLevel(0f), 0f)
        assertEquals(0f, VolumeScale.gainToLevel(gainOfDb(-60f)), 0f) // below the bottom is the bottom
    }

    @Test
    fun `level and gain convert back and forth`() {
        for (step in 1..16) {
            val level = step / 16f
            assertEquals(level, VolumeScale.gainToLevel(VolumeScale.levelToGain(level)), 1e-4f)
        }
        assertEquals(0f, VolumeScale.levelToGain(0f), 0f)
        assertEquals(1f, VolumeScale.levelToGain(1f), 1e-6f)
        assertEquals(-15f, 20f * log10(VolumeScale.levelToGain(0.5f)), 1e-3f)
    }

    @Test
    fun `the same reported volume is not a move`() {
        assertTrue(VolumeScale.sameGain(0.5f, 0.5f))
        assertTrue(VolumeScale.sameGain(gainOfDb(-18.75f), gainOfDb(-18.76f)))
        assertTrue(VolumeScale.sameGain(0f, 0f))
    }

    @Test
    fun `one slider step is a move`() {
        // the phone's slider moves in steps of 1.875 dB, at the loud end and at the quiet end
        assertFalse(VolumeScale.sameGain(gainOfDb(0f), gainOfDb(-1.875f)))
        assertFalse(VolumeScale.sameGain(gainOfDb(-28.125f), gainOfDb(-30f)))
        assertFalse(VolumeScale.sameGain(0f, gainOfDb(-30f)))
    }

    @Test
    fun `remote steps move the slider and stop at the ends`() {
        assertEquals(0.5f, VolumeScale.stepped(0.5f, 0, 16), 1e-6f)
        assertEquals(0.5625f, VolumeScale.stepped(0.5f, 1, 16), 1e-6f)
        assertEquals(0.4375f, VolumeScale.stepped(0.5f, -1, 16), 1e-6f)
        assertEquals(1f, VolumeScale.stepped(0.95f, 5, 16), 0f)
        // the old pin to silence: a system number stuck at 0 read as eight steps down, every look
        assertEquals(0f, VolumeScale.stepped(0.7f, -8 * 16, 16), 0f)
    }
}
