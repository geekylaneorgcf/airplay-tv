package io.github.besliky.airplaytv.ui

import io.github.besliky.airplaytv.ui.PresenceTimeline.Stage
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceTimelineTest {

    private val t = PresenceTimeline.STANDARD
    private val minute = 60_000L

    @Test
    fun `a screen in use stays bright`() {
        assertEquals(Stage.ACTIVE, t.stageFor(0, 0))
        assertEquals(Stage.ACTIVE, t.stageFor(2 * minute + 59_000, 0))
    }

    @Test
    fun `it dims after three minutes, goes minimal after eight and black after half an hour`() {
        assertEquals(Stage.DIM, t.stageFor(3 * minute, 0))
        assertEquals(Stage.DIM, t.stageFor(8 * minute - 1, 0))
        assertEquals(Stage.MINIMAL, t.stageFor(8 * minute, 0))
        assertEquals(Stage.MINIMAL, t.stageFor(30 * minute - 1, 0))
        assertEquals(Stage.BLACK, t.stageFor(30 * minute, 0))
        assertEquals(Stage.BLACK, t.stageFor(6 * 60 * minute, 0))
    }

    @Test
    fun `a long pause lets the display switch off, but only once the screen is already minimal`() {
        assertEquals(Stage.DIM, t.stageFor(4 * minute, 20 * minute)) // someone just touched it: keep it lit
        assertEquals(Stage.BLANK, t.stageFor(8 * minute, 15 * minute))
        assertEquals(Stage.MINIMAL, t.stageFor(8 * minute, 14 * minute))
        assertEquals(Stage.BLANK, t.stageFor(40 * minute, 15 * minute)) // pause beats the playing-black stage
    }

    @Test
    fun `the stages only ever deepen as idle time grows`() {
        var last = 0
        for (idle in 0L..(40 * minute) step 5_000L) {
            val rank = t.stageFor(idle, 0).ordinal
            assertEquals("went back up at ${idle / 1000}s", true, rank >= last)
            last = rank
        }
    }

    @Test
    fun `a faster timeline keeps the order and shrinks every step`() {
        val fast = t.faster(60)
        assertEquals(Stage.ACTIVE, fast.stageFor(2_900, 0))
        assertEquals(Stage.DIM, fast.stageFor(3_000, 0))
        assertEquals(Stage.MINIMAL, fast.stageFor(8_000, 0))
        assertEquals(Stage.BLACK, fast.stageFor(30_000, 0))
        assertEquals(Stage.BLANK, fast.stageFor(8_000, 15_000))
    }

    @Test
    fun `the minimal stage starts where the screensaver hands over`() {
        // the player starts with its idle clock set back by this much and is minimal at once
        assertEquals(8 * minute, t.minimalAfterMs)
        assertEquals(Stage.MINIMAL, t.stageFor(t.minimalAfterMs, 0))
        assertEquals(8_000L, t.faster(60).minimalAfterMs)
    }

    @Test
    fun `a factor below one changes nothing`() {
        assertEquals(Stage.MINIMAL, t.faster(0).stageFor(8 * minute, 0))
    }

    @Test
    fun `a pause of a minute lets the screen rest, but only when nobody is at the remote`() {
        assertEquals(Stage.ACTIVE, t.stageFor(2 * minute, 59_000))
        assertEquals(Stage.REST, t.stageFor(2 * minute, minute))
        assertEquals(Stage.ACTIVE, t.stageFor(30_000, 5 * minute)) // the pause was just pressed on the remote
        assertEquals(Stage.ACTIVE, t.stageFor(2 * minute, 0)) // playing: no rest
        assertEquals(Stage.DIM, t.stageFor(3 * minute, 10 * minute)) // the deeper stages win
        assertEquals(Stage.REST, t.faster(60).stageFor(1_000, 1_000))
    }
}
