package io.github.besliky.airplaytv.ui

/**
 * How the player moves when the song changes, kept apart from the views so the rules can be tested.
 *
 * A skip reaches the receiver in steps: the new title and artist, then a gap of 100 to 200 ms in which the
 * audio runs dry (reported as a pause), then the album, the progress and the cover. Showing every step as it
 * came made the cover dip, spring back and slide in all at once.
 */
object TrackMotion {

    /** A pause this soon after the song changed is most likely the gap between two songs. */
    const val GAP_MS = 1500L

    /** How long a pause inside that window has to last before the player shows it. */
    const val PAUSE_HOLD_MS = 900L

    /** How long the cover takes to slide in; a cover that arrives meanwhile takes its place. */
    const val SLIDE_MS = 460L

    /** How long a song may go without a cover before the old one is taken away. */
    const val COVER_WAIT_MS = 4000L

    /**
     * How long to wait before showing that the music paused: a pause in the moments after the song changed is
     * held back, so the gap between two songs never shows; a pause at any other time shows at once.
     */
    fun pauseHoldMs(sinceTrackChangeMs: Long): Long = if (sinceTrackChangeMs in 0..GAP_MS) PAUSE_HOLD_MS else 0L

    enum class CoverMove { KEEP, SLIDE, REPLACE }

    /**
     * What a cover that has just arrived should do. The same picture again (the next song of an album, or a
     * sender that sends it twice) stays where it is; one that arrives while the last is still sliding in takes
     * its place on the spot, so a quick run of skips ends in one slide rather than a pile of them.
     */
    fun coverMove(samePicture: Boolean, sinceSlideStartMs: Long): CoverMove = when {
        samePicture -> CoverMove.KEEP
        sinceSlideStartMs in 0 until SLIDE_MS -> CoverMove.REPLACE
        else -> CoverMove.SLIDE
    }
}
