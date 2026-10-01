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
        assertEquals(Stage.ACTIVE, t.stageFor(minute + 59_000, 0))
    }

    @Test
    fun `it dims after two minutes, goes minimal after five and black after half an hour`() {
        assertEquals(Stage.DIM, t.stageFor(2 * minute, 0))
        assertEquals(Stage.DIM, t.stageFor(5 * minute - 1, 0))
        assertEquals(Stage.MINIMAL, t.stageFor(5 * minute, 0))
        assertEquals(Stage.MINIMAL, t.stageFor(30 * minute - 1, 0))
        assertEquals(Stage.BLACK, t.stageFor(30 * minute, 0))
        assertEquals(Stage.BLACK, t.stageFor(6 * 60 * minute, 0))
    }

    @Test
    fun `a long pause lets the display switch off, but only once the screen is already minimal`() {
        assertEquals(Stage.DIM, t.stageFor(3 * minute, 20 * minute)) // someone just touched it: keep it lit
        assertEquals(Stage.BLANK, t.stageFor(5 * minute, 15 * minute))
        assertEquals(Stage.MINIMAL, t.stageFor(5 * minute, 14 * minute))
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
        assertEquals(Stage.ACTIVE, fast.stageFor(1_900, 0))
        assertEquals(Stage.DIM, fast.stageFor(2_000, 0))
        assertEquals(Stage.MINIMAL, fast.stageFor(5_000, 0))
        assertEquals(Stage.BLACK, fast.stageFor(30_000, 0))
        assertEquals(Stage.BLANK, fast.stageFor(5_000, 15_000))
    }

    @Test
    fun `a factor below one changes nothing`() {
        assertEquals(Stage.MINIMAL, t.faster(0).stageFor(5 * minute, 0))
    }
}
