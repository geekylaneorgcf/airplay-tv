package io.github.besliky.airplaytv.service

/**
 * The TV's own volume, moved by the phone's slider and the remote's keys. The phone does not set the TV to a number of its own: the
 * slider at 70 % would be a TV volume of 70, which is very loud on a TV that was at 15. Instead the slider moves the TV between
 * zero and the volume it had when the phone connected (the reference), so the phone can turn the TV down and bring it back up,
 * never above what its owner had set. A volume moved on the TV itself, with its own remote, moves the reference along.
 */
object TvVolume {

    /** The TV volume (0 to 100) for the slider position [level] (0 to 1, the ceiling of the volume limits already applied) under [reference]. */
    fun target(level: Float, reference: Int): Int =
        Math.round(level.coerceIn(0f, 1f) * reference.coerceIn(0, 100)).coerceIn(0, 100)

    /**
     * The reference after the volume was moved on the TV itself to [tvVolume] while the slider stood at [level]: the slider's top is
     * where it would now go. Null when the slider stood too low for that to be told.
     */
    fun referenceAfterTvChange(tvVolume: Int, level: Float): Int? =
        if (level < MIN_LEVEL_TO_TELL) null else Math.round(tvVolume.coerceIn(0, 100) / level).coerceIn(1, 100)

    private const val MIN_LEVEL_TO_TELL = 0.1f
}
