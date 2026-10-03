package io.github.besliky.airplaytv.service

/** The arithmetic of the sleep timer: when it warns, when it ends, and when a key press buys more time. Pure, so it is tested without a clock. */
object SleepTimerMath {

    const val WARN_FIRST_MS = 5 * 60_000L
    const val WARN_LAST_MS = 60_000L
    const val EXTEND_MS = 15 * 60_000L

    /** The minutes the timer offers; 0 is off. */
    val CHOICES = listOf(0, 30, 60, 90)

    enum class Stage { FIVE_MINUTES, ONE_MINUTE, END }

    class Step(val atMs: Long, val stage: Stage)

    /** What is still to happen for a timer that ends at [endMs], seen at [nowMs], in order: a warning that is already past is not repeated. */
    fun steps(nowMs: Long, endMs: Long): List<Step> {
        val found = ArrayList<Step>(3)
        if (endMs - WARN_FIRST_MS > nowMs) found.add(Step(endMs - WARN_FIRST_MS, Stage.FIVE_MINUTES))
        if (endMs - WARN_LAST_MS > nowMs) found.add(Step(endMs - WARN_LAST_MS, Stage.ONE_MINUTE))
        if (endMs > nowMs) found.add(Step(endMs, Stage.END))
        return found
    }

    /** True in the last minute before the end, when a key press extends the timer. */
    fun inLastMinute(nowMs: Long, endMs: Long): Boolean = endMs > 0 && nowMs >= endMs - WARN_LAST_MS && nowMs < endMs

    /** "42 min" for what is left (rounded up), "1 min" in the last minute. */
    fun left(nowMs: Long, endMs: Long): String {
        val minutes = ((endMs - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L
        return "${minutes.coerceAtLeast(1L)} min"
    }
}
