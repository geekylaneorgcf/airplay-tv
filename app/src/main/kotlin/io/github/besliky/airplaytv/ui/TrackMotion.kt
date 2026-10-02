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
    const val SLIDE_MS = 640L

    /**
     * The whole change of song is one motion: the cover, the three lines of text and the colours all start together
     * and end together, in this long. Nothing moves before that, see [commitDelayMs].
     */
    const val SHOT_MS = 640L

    /** The longest a song change waits for its cover (and album) before it is shown with what there is. */
    const val HOLD_MS = 1100L

    /** After the cover is in, how long the album and the progress get to follow, so they join the same motion. */
    const val SETTLE_MS = 160L

    /** How far into the motion the old text is gone and the new text starts to come in. */
    const val TEXT_SWAP_AT = 0.28f

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

    /**
     * When a song change that has been held back is shown, as a delay from [now]. A sender tells a skip in pieces over
     * a second or so (title, album, progress, cover), and showing each piece as it came was five small motions. The
     * change waits instead: until the cover is in ([readyAt], 0 while it is not) plus a moment for the rest, but never
     * longer than [HOLD_MS] from [beganAt].
     */
    fun commitDelayMs(beganAt: Long, readyAt: Long, now: Long): Long {
        val deadline = beganAt + HOLD_MS
        // a cover that came before the title counts from the title: the album and the progress still follow it
        val at = if (readyAt > 0L) minOf(maxOf(readyAt, beganAt) + SETTLE_MS, deadline) else deadline
        return (at - now).coerceAtLeast(0L)
    }

    /** Fast at first and settling gently (ease-out quintic), 0 to 1 over [t] from 0 to 1. */
    fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u * u * u
    }

    /** Slow at first and speeding up (ease-in quadratic), 0 to 1 over [t] from 0 to 1. */
    fun easeIn(t: Float): Float = t.coerceIn(0f, 1f).let { it * it }

    /**
     * Where one line of the text is in its motion at [p] (0 to 1 over the whole shot): how far it has left (alpha 1
     * to 0 and a slide away) or come back (0 to 1, sliding in). Line [index] (0 title, 1 artist, 2 album) starts coming
     * in a little after the one above it. Returns alpha and the share of the slide still to do, from -1 (gone, to the
     * side it left) through 0 (in place) to 1 (waiting at the side it comes in from).
     */
    fun textAt(p: Float, index: Int): Pair<Float, Float> {
        if (p < TEXT_SWAP_AT) {
            val t = p / TEXT_SWAP_AT
            return (1f - t) to -easeIn(t)
        }
        val start = TEXT_SWAP_AT + 0.04f * index
        val e = easeOut(((p - start) / (1f - start)).coerceIn(0f, 1f))
        return e to (1f - e)
    }
}
