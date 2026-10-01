package io.github.besliky.airplaytv.ui

/**
 * The arithmetic of seeking with the remote's left and right keys, kept apart from the views so it can be
 * tested. A tap jumps ten seconds; holding the key scrubs, faster the longer it is held, the way Apple
 * TV's remote does. Nothing is sent to the phone while keys are still being pressed: the target moves, and
 * the player shows it, until the keys have been quiet for [COMMIT_MS].
 */
object SeekMath {

    /** A single press of left or right. */
    const val TAP_MS = 10_000L

    /** How long after the last key event the seek is sent. */
    const val COMMIT_MS = 400L

    /** A seek never lands closer to the end than this, so it cannot skip the song by accident. */
    const val END_MARGIN_MS = 1_500L

    /** How close the sender's reported position must be to the target to count as the seek having happened. */
    const val ARRIVED_WITHIN_MS = 4_000L

    /** Seconds of song moved per second of holding, by how long the key has been held. */
    fun holdRateMsPerSecond(heldMs: Long): Long = when {
        heldMs < 1_500 -> 20_000
        heldMs < 4_000 -> 40_000
        else -> 80_000
    }

    /**
     * How far one key event moves the target: a tap (the first event) is [TAP_MS]; the repeats of a held
     * key move by the hold rate for the time since the previous event, so the speed does not depend on how
     * often the remote repeats the key.
     */
    fun step(repeatCount: Int, heldMs: Long, sinceLastEventMs: Long): Long =
        if (repeatCount == 0) TAP_MS else holdRateMsPerSecond(heldMs) * sinceLastEventMs.coerceIn(0, 200) / 1_000

    /** The new target: [from] moved by [step] in [direction] (1 forward, -1 back), kept inside the song. */
    fun target(from: Long, direction: Int, step: Long, durationMs: Long): Long {
        val last = (durationMs - END_MARGIN_MS).coerceAtLeast(0)
        return (from + direction * step).coerceIn(0, last)
    }

    /** Whether a position the sender reported is where the seek was meant to go. */
    fun arrived(reportedMs: Long, targetMs: Long): Boolean = kotlin.math.abs(reportedMs - targetMs) <= ARRIVED_WITHIN_MS
}
