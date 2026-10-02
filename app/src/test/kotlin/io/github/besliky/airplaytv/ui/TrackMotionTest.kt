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

    @Test
    fun `a song change waits for the cover and a moment more`() {
        // began at 1000, no cover yet: the deadline, 1100 ms on
        assertEquals(TrackMotion.HOLD_MS, TrackMotion.commitDelayMs(beganAt = 1000, readyAt = 0, now = 1000))
        assertEquals(TrackMotion.HOLD_MS - 300, TrackMotion.commitDelayMs(1000, 0, 1300))
        // the cover came at 1400: a moment later, so the album and the progress can join
        assertEquals(TrackMotion.SETTLE_MS, TrackMotion.commitDelayMs(1000, readyAt = 1400, now = 1400))
        assertEquals(TrackMotion.SETTLE_MS - 100, TrackMotion.commitDelayMs(1000, 1400, 1500))
    }

    @Test
    fun `a late cover cannot hold the change past the deadline, and nothing is negative`() {
        // the deadline was 2100: 50 ms left, though the settle alone would be 160
        assertEquals(50L, TrackMotion.commitDelayMs(1000, readyAt = 2000, now = 2050))
        assertEquals(0L, TrackMotion.commitDelayMs(1000, 0, 5000))
        // a cover that came before the change (readyAt in the past) is shown after the settle only
        assertEquals(0L, TrackMotion.commitDelayMs(1000, readyAt = 900, now = 1000))
    }

    @Test
    fun `the easings run from 0 to 1 and never overshoot`() {
        assertEquals(0f, TrackMotion.easeOut(0f), 0f)
        assertEquals(1f, TrackMotion.easeOut(1f), 0f)
        assertEquals(0f, TrackMotion.easeIn(0f), 0f)
        assertEquals(1f, TrackMotion.easeIn(1f), 0f)
        var last = -1f
        for (i in 0..20) {
            val v = TrackMotion.easeOut(i / 20f)
            assertTrue(v >= last && v <= 1f)
            last = v
        }
        assertEquals(0f, TrackMotion.easeOut(-3f), 0f)
        assertEquals(1f, TrackMotion.easeOut(7f), 0f)
    }

    @Test
    fun `the text leaves, then comes back one line after another`() {
        // in place at the start, gone just before the swap, in place at the end
        val start = TrackMotion.textAt(0f, 0)
        assertEquals(1f, start.first, 0f)
        assertEquals(0f, start.second, 0f)
        val gone = TrackMotion.textAt(TrackMotion.TEXT_SWAP_AT - 0.0001f, 0)
        assertEquals(0f, gone.first, 0.01f)
        assertEquals(-1f, gone.second, 0.01f)
        val done = TrackMotion.textAt(1f, 2)
        assertEquals(1f, done.first, 1e-6f)
        assertEquals(0f, done.second, 1e-6f)
        // just after the swap the title is already coming in and the album has not started
        val title = TrackMotion.textAt(TrackMotion.TEXT_SWAP_AT + 0.1f, 0).first
        val album = TrackMotion.textAt(TrackMotion.TEXT_SWAP_AT + 0.1f, 2).first
        assertTrue(title > album)
    }
}
