package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeLimitsTest {

    @Test
    fun `night is a window of hours that may run over midnight`() {
        assertTrue(VolumeLimits.inNight(23, 22, 7))
        assertTrue(VolumeLimits.inNight(3, 22, 7))
        assertFalse(VolumeLimits.inNight(7, 22, 7))
        assertFalse(VolumeLimits.inNight(12, 22, 7))
        assertTrue(VolumeLimits.inNight(1, 0, 6))
        assertFalse(VolumeLimits.inNight(6, 0, 6))
        assertFalse(VolumeLimits.inNight(5, 5, 5))
    }

    @Test
    fun `the ceiling is the day's, or the night's while it is night`() {
        val config = VolumeLimits.Config(maxPercent = 80, nightMaxPercent = 40, nightFromHour = 22, nightToHour = 7)
        assertEquals(80, VolumeLimits.ceilingPercent(config, 15))
        assertEquals(40, VolumeLimits.ceilingPercent(config, 23))
        assertEquals(40, VolumeLimits.ceilingPercent(config, 2))
    }

    @Test
    fun `a night ceiling above the day's does not raise it`() {
        val config = VolumeLimits.Config(maxPercent = 50, nightMaxPercent = 70)
        assertEquals(50, VolumeLimits.ceilingPercent(config, 23))
    }

    @Test
    fun `without limits nothing changes`() {
        val config = VolumeLimits.Config()
        assertEquals(100, VolumeLimits.ceilingPercent(config, 23))
        assertEquals(0.37f, VolumeLimits.apply(0.37f, 100), 0.0001f)
        assertEquals(0.9f, VolumeLimits.startLevel(0.9f, config), 0.0001f)
    }

    @Test
    fun `the whole slider is scaled into the ceiling`() {
        assertEquals(0.6f, VolumeLimits.apply(1f, 60), 0.0001f)
        assertEquals(0.3f, VolumeLimits.apply(0.5f, 60), 0.0001f)
        assertEquals(0f, VolumeLimits.apply(0f, 60), 0.0001f)
        assertEquals(0.6f, VolumeLimits.apply(1.4f, 60), 0.0001f)
    }

    @Test
    fun `a session starts at the start level when the phone is louder`() {
        val config = VolumeLimits.Config(startPercent = 30)
        assertEquals(0.3f, VolumeLimits.startLevel(0.8f, config), 0.0001f)
        assertEquals(0.2f, VolumeLimits.startLevel(0.2f, config), 0.0001f)
    }

    @Test
    fun `the TV's own volume follows the same scale`() {
        assertEquals(60, VolumeLimits.tvVolume(1f, 60))
        assertEquals(30, VolumeLimits.tvVolume(0.5f, 60))
        assertEquals(0, VolumeLimits.tvVolume(0f, 60))
        assertEquals(100, VolumeLimits.tvVolume(1f, 100))
    }
}
