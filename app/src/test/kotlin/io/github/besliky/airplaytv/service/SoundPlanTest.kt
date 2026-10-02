package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundPlanTest {

    private val centres = intArrayOf(60, 230, 910, 3600, 14000)

    @Test
    fun `bass lifts the low bands and fades out by one kilohertz`() {
        val levels = SoundPlan.bandLevels(centres, -1500, 1500, bassSteps = 2, trebleSteps = 0)
        assertEquals(600, levels[0])
        assertEquals(600, levels[1])
        assertEquals(72, levels[2]) // 910 Hz is almost out of reach of the bass
        assertEquals(0, levels[3])
        assertEquals(0, levels[4])
    }

    @Test
    fun `treble lifts the high bands and fades out by one kilohertz`() {
        val levels = SoundPlan.bandLevels(centres, -1500, 1500, bassSteps = 0, trebleSteps = 3)
        assertEquals(0, levels[0])
        assertEquals(0, levels[1])
        assertEquals(0, levels[2])
        assertEquals(780, levels[3]) // 3600 Hz is 0.87 of the way up
        assertEquals(900, levels[4])
    }

    @Test
    fun `a cut is as large as a lift and every level stays inside what the equalizer takes`() {
        val cut = SoundPlan.bandLevels(centres, -500, 500, bassSteps = -3, trebleSteps = -3)
        assertEquals(listOf(-500, -500, -108, -500, -500), cut.toList())
        assertTrue(cut.all { it in -500..500 })
        assertEquals(0, SoundPlan.bandLevels(centres, -500, 500, 0, 0).sum())
    }

    @Test
    fun `the loudness boost has three steps`() {
        assertEquals(listOf(0, 300, 600, 900), (0..3).map { SoundPlan.loudnessGainMb(it) })
        assertEquals(900, SoundPlan.loudnessGainMb(9))
        assertEquals(0, SoundPlan.loudnessGainMb(-2))
    }
}
