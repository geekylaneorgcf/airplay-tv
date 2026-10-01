package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncMathTest {

    private val rate = 44_100L

    @Test
    fun `the sound heard is behind the newest packet by what is queued`() {
        // newest packet is 30 s into the song, 600 ms of audio is queued, the sender said 29 s: the sound is at 29.4 s
        val start = 1_000_000L
        val last = start + 30 * rate
        assertEquals(400L, SyncMath.offsetMs(start, 29_000, last, 0, 600, rate))
    }

    @Test
    fun `time since the newest packet counts as played`() {
        val start = 5L
        val last = start + 10 * rate
        assertEquals(50L + 100L, SyncMath.offsetMs(start, 9_450, last, 100, 500, rate))
    }

    @Test
    fun `a sound earlier than reported gives a negative offset`() {
        val start = 0L
        val last = 20 * rate
        assertEquals(-1_000L, SyncMath.offsetMs(start, 20_000, last, 0, 1_000, rate))
    }

    @Test
    fun `timestamps that wrap around 32 bits still measure`() {
        val start = 0xFFFFFFFFL - 10 * rate
        val last = (start + 20 * rate) and 0xFFFFFFFFL
        assertEquals(0L, SyncMath.offsetMs(start, 19_500, last, 0, 500, rate))
    }

    @Test
    fun `a stale packet or an absurd result is not used`() {
        val start = 0L
        val last = 20 * rate
        assertNull(SyncMath.offsetMs(start, 20_000, last, 1_500, 500, rate))
        assertNull(SyncMath.offsetMs(start, 5_000, last, 0, 500, rate))
        assertNull(SyncMath.offsetMs(start, 20_000, last, -1, 500, rate))
        assertNull(SyncMath.offsetMs(start, 20_000, last, 0, 500, 0))
        // the newest packet is before the song's start: the difference wraps to a huge value
        assertNull(SyncMath.offsetMs(10 * rate, 1_000, 5 * rate, 0, 500, rate))
    }

    @Test
    fun `the median ignores one odd value`() {
        assertEquals(305L, SyncMath.median(listOf(300, 310, 290, 2_000, 305)))
        assertEquals(0L, SyncMath.median(emptyList()))
    }
}
