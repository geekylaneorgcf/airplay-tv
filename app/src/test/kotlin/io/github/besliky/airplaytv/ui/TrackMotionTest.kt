package io.github.besliky.airplaytv.ui

import io.github.besliky.airplaytv.ui.TrackMotion.CoverMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMotionTest {

    @Test
    fun `a pause right after the song changed is held back`() {
        // the gaps seen from YouTube Music: a pause 180 to 400 ms after the new title, lasting 110 to 190 ms
        assertEquals(TrackMotion.PAUSE_HOLD_MS, TrackMotion.pauseHoldMs(0))
        assertEquals(TrackMotion.PAUSE_HOLD_MS, TrackMotion.pauseHoldMs(180))
        assertEquals(TrackMotion.PAUSE_HOLD_MS, TrackMotion.pauseHoldMs(TrackMotion.GAP_MS))
    }

    @Test
    fun `a pause at any other time shows at once`() {
        assertEquals(0L, TrackMotion.pauseHoldMs(TrackMotion.GAP_MS + 1))
        assertEquals(0L, TrackMotion.pauseHoldMs(60_000))
        assertEquals(0L, TrackMotion.pauseHoldMs(-1)) // a clock that went backwards
    }

    @Test
    fun `the hold is longer than the gaps it is meant to hide`() {
        assertTrue(TrackMotion.PAUSE_HOLD_MS > 400)
    }

    @Test
    fun `the same picture again does not move`() {
        assertEquals(CoverMove.KEEP, TrackMotion.coverMove(samePicture = true, sinceSlideStartMs = 10_000))
        assertEquals(CoverMove.KEEP, TrackMotion.coverMove(samePicture = true, sinceSlideStartMs = 100))
    }

    @Test
    fun `a new cover slides in when nothing else is moving`() {
        assertEquals(CoverMove.SLIDE, TrackMotion.coverMove(false, TrackMotion.SLIDE_MS))
        assertEquals(CoverMove.SLIDE, TrackMotion.coverMove(false, 30_000))
    }

    @Test
    fun `a cover that arrives mid slide takes the place of the one sliding in`() {
        assertEquals(CoverMove.REPLACE, TrackMotion.coverMove(false, 0))
        assertEquals(CoverMove.REPLACE, TrackMotion.coverMove(false, TrackMotion.SLIDE_MS - 1))
    }

    @Test
    fun `a clock that went backwards still slides`() {
        assertEquals(CoverMove.SLIDE, TrackMotion.coverMove(false, -5))
    }
}
