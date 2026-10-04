package io.github.besliky.airplaytv.service

/**
 * The TV's own volume, moved by the phone's slider and the remote's keys. The phone does not set the TV to a number of its own: the
 * slider at 70 % would be a TV volume of 70, which is very loud on a TV that was at 15. Instead the TV stays at the volume it had when the
 * phone connected (the reference) for as long as the slider stands where it stood then (its home), and the slider turns it down from
 * there and back up to it, never above what its owner had set. A phone that has not played to this receiver before starts its slider
 * low (a third of the way up), and the TV must not be turned down to a third of its volume for that: the sound would be gone before
 * anything was touched. A volume moved on the TV itself, with its own remote, moves the reference along.
 */
object TvVolume {

    /**
     * The slider position that counts as the TV's own volume: where the phone's slider stood when the session began. A slider that
     * began nearly at the bottom (or muted) is not taken as home, or the smallest move would be the whole range.
     */
    fun home(firstLevel: Float): Float = firstLevel.coerceIn(MIN_HOME, 1f)

    /**
     * The part of the reference the TV is set to (0 to 1): all of it at [home] and above, less below it, scaled into the volume limits'
     * ceiling ([ceiling], 0 to 1; the whole slider is scaled into it, so home is the ceiling and half of home is half of it).
     */
    fun fraction(level: Float, home: Float, ceiling: Float): Float =
        (level.coerceIn(0f, 1f) / home.coerceIn(MIN_HOME, 1f)).coerceAtMost(1f) * ceiling.coerceIn(0f, 1f)

    /** The TV volume (0 to 100) for the part [fraction] (0 to 1) of the [reference]. */
    fun target(fraction: Float, reference: Int): Int =
        Math.round(fraction.coerceIn(0f, 1f) * reference.coerceIn(0, 100)).coerceIn(0, 100)

    /**
     * The reference after the volume was moved on the TV itself to [tvVolume] while the TV stood at the part [fraction] of it: the top is
     * where it would now go. Null when the fraction was too small for that to be told.
     */
    fun referenceAfterTvChange(tvVolume: Int, fraction: Float): Int? =
        if (fraction < MIN_LEVEL_TO_TELL) null else Math.round(tvVolume.coerceIn(0, 100) / fraction).coerceIn(1, 100)

    /**
     * What the TV is owed from a session that ended without giving its volume back (the receiver was stopped, or the TV did not answer):
     * [record] is "the volume the receiver put it at,the volume it had", and when the TV [current] still stands where the receiver put
     * it, nobody has touched it since and it goes back to the volume it had. Null when nothing is owed, or when somebody set it since.
     */
    fun leftBehind(record: String, current: Int): Int? {
        val parts = record.split(',')
        if (parts.size != 2) return null
        val put = parts[0].trim().toIntOrNull() ?: return null
        val had = parts[1].trim().toIntOrNull() ?: return null
        return if (current == put && had > put && had in 1..100) had else null
    }

    private const val MIN_LEVEL_TO_TELL = 0.1f
    private const val MIN_HOME = 0.25f
}
