package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleMathTest {

    @Test
    fun `the night never raises the brightness`() {
        assertEquals(30, ScheduleMath.oledTarget(80, 30))
        assertNull(ScheduleMath.oledTarget(30, 30))
        assertNull(ScheduleMath.oledTarget(20, 30))
    }

    @Test
    fun `what the night changed survives being written down`() {
        val record = ScheduleMath.oledRecord("oledLight", 80, 30)
        assertEquals(Triple("oledLight", 80, 30), ScheduleMath.parseOled(record))
        assertNull(ScheduleMath.parseOled(""))
        assertNull(ScheduleMath.parseOled("oledLight,80"))
        assertNull(ScheduleMath.parseOled("oledLight,x,30"))
        assertNull(ScheduleMath.parseOled(",80,30"))
    }

    @Test
    fun `the morning puts the brightness back only if nobody touched it`() {
        val record = Triple("oledLight", 80, 30)
        assertEquals(80, ScheduleMath.oledRestore(record, 30))
        // the owner set it since: not ours to move
        assertNull(ScheduleMath.oledRestore(record, 45))
        assertNull(ScheduleMath.oledRestore(record, null))
    }

    @Test
    fun `the idle turn-off warns once, waits, and then turns the TV off`() {
        val hour = 3_600_000L
        val warn = 120_000L
        assertEquals(ScheduleMath.Idle.ACTIVE, ScheduleMath.idleStep(2 * hour - 1, 0, 2 * hour, 0, warn))
        assertEquals(ScheduleMath.Idle.WARN, ScheduleMath.idleStep(2 * hour, 0, 2 * hour, 0, warn))
        assertEquals(ScheduleMath.Idle.WAITING, ScheduleMath.idleStep(2 * hour + 60_000, 0, 2 * hour, 2 * hour, warn))
        assertEquals(ScheduleMath.Idle.TURN_OFF, ScheduleMath.idleStep(2 * hour + warn, 0, 2 * hour, 2 * hour, warn))
    }

    @Test
    fun `a key during the warning cancels it`() {
        val hour = 3_600_000L
        // the key came at 2 h 30 s: the stick is active again, whatever the warning said
        assertEquals(ScheduleMath.Idle.ACTIVE, ScheduleMath.idleStep(2 * hour + 90_000, 2 * hour + 30_000, 2 * hour, 2 * hour, 120_000))
    }
}
