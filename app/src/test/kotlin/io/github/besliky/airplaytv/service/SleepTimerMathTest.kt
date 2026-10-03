package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerMathTest {

    private val minute = 60_000L

    @Test
    fun `a long timer warns at five minutes, at one minute and ends`() {
        val steps = SleepTimerMath.steps(0, 60 * minute)
        assertEquals(listOf(55 * minute, 59 * minute, 60 * minute), steps.map { it.atMs })
        assertEquals(
            listOf(SleepTimerMath.Stage.FIVE_MINUTES, SleepTimerMath.Stage.ONE_MINUTE, SleepTimerMath.Stage.END),
            steps.map { it.stage },
        )
    }

    @Test
    fun `a warning that is already past is not given again`() {
        val steps = SleepTimerMath.steps(57 * minute, 60 * minute)
        assertEquals(listOf(SleepTimerMath.Stage.ONE_MINUTE, SleepTimerMath.Stage.END), steps.map { it.stage })
        assertEquals(listOf(SleepTimerMath.Stage.END), SleepTimerMath.steps(59 * minute + 30_000, 60 * minute).map { it.stage })
        assertTrue(SleepTimerMath.steps(61 * minute, 60 * minute).isEmpty())
    }

    @Test
    fun `a key adds time only in the last minute`() {
        val end = 60 * minute
        assertFalse(SleepTimerMath.inLastMinute(30 * minute, end))
        assertFalse(SleepTimerMath.inLastMinute(58 * minute, end))
        assertTrue(SleepTimerMath.inLastMinute(59 * minute, end))
        assertTrue(SleepTimerMath.inLastMinute(60 * minute - 1, end))
        assertFalse(SleepTimerMath.inLastMinute(60 * minute, end))
        assertFalse(SleepTimerMath.inLastMinute(10, 0)) // no timer
    }

    @Test
    fun `what is left is rounded up and never shows zero`() {
        assertEquals("42 min", SleepTimerMath.left(0, 42 * minute))
        assertEquals("42 min", SleepTimerMath.left(0, 41 * minute + 1))
        assertEquals("1 min", SleepTimerMath.left(0, 5_000))
        assertEquals("1 min", SleepTimerMath.left(10 * minute, 5 * minute))
    }
}
