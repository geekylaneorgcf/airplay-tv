package io.github.besliky.airplaytv.service

/**
 * The TV's own volume, moved by the phone's slider and the remote's keys. The slider is the TV's real scale: the slider at 70 % is a TV
 * volume of 70, the whole of 0 to 100 (the volume limits' ceiling scales the slider into less, see [VolumeLimits]). Until the slider is
 * moved the TV stays at the volume it had when the phone connected: a phone that has not played to this receiver before starts its
 * slider low (a third of the way up), and the TV must not jump to a third, or to whatever the slider remembers, before anything was touched.
 * The remote's volume keys step the TV from the volume it has now, one step at a time.
 */
object TvVolume {

    /** Whether the slider at [level] has been moved from [first], where it stood when the session began (a hair's breadth is not a move). */
    fun moved(level: Float, first: Float): Boolean = first < 0f || Math.abs(level - first) > MOVE_EPSILON

    /** The TV volume (0 to 100) the slider position [level] stands for under the ceiling [ceilingPercent]: the real scale, scaled into the ceiling. */
    fun absolute(level: Float, ceilingPercent: Int): Int =
        Math.round(VolumeLimits.apply(level, ceilingPercent) * 100f).coerceIn(0, 100)

    /** The TV volume while the slider has not been moved: what the TV had, though not above the ceiling. */
    fun atHome(reference: Int, ceilingPercent: Int): Int = minOf(reference.coerceIn(0, 100), ceilingPercent.coerceIn(MIN_CEILING, 100))

    /** The slider position that stands for the TV volume [tvVolume] under [ceilingPercent]: the inverse of [absolute]. */
    fun levelFor(tvVolume: Int, ceilingPercent: Int): Float =
        (tvVolume.coerceIn(0, 100) / ceilingPercent.coerceIn(MIN_CEILING, 100).toFloat()).coerceIn(0f, 1f)

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

    private const val MOVE_EPSILON = 0.02f
    private const val MIN_CEILING = 10
}
