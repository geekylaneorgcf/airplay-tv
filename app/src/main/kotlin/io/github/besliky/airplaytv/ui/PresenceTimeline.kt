package io.github.besliky.airplaytv.ui

/**
 * How the Now Playing screen protects an OLED panel while nobody uses the remote.
 *
 * The clock is the time since the last real interaction: a remote key, or the volume actually
 * moving. A new song, or the sender repeating its volume, does not reset it. In order:
 *
 * - REST: while the music is paused for a while the cover dims to about half and the screen drifts more slowly, so a pause never
 *   leaves a bright still picture on the panel;
 * - DIM: everything at about a third of its brightness;
 * - MINIMAL: black, with only what changes lit, small and dim: the song, the progress and the times;
 * - BLACK: nothing lit at all. The screen stays on so the TV keeps playing the sound;
 * - BLANK: like BLACK, but the music is paused, so the system may switch the display off.
 */
class PresenceTimeline(
    private val dimAfterMs: Long,
    val minimalAfterMs: Long,
    private val blackAfterMs: Long,
    private val blankAfterPausedMs: Long,
    private val restAfterPausedMs: Long = 60_000L,
) {
    enum class Stage { ACTIVE, REST, DIM, MINIMAL, BLACK, BLANK }

    /** The stage after [idleMs] without interaction, with the music paused for [pausedMs] (0 while it plays). */
    fun stageFor(idleMs: Long, pausedMs: Long): Stage = when {
        pausedMs >= blankAfterPausedMs && idleMs >= minimalAfterMs -> Stage.BLANK
        idleMs >= blackAfterMs -> Stage.BLACK
        idleMs >= minimalAfterMs -> Stage.MINIMAL
        idleMs >= dimAfterMs -> Stage.DIM
        pausedMs >= restAfterPausedMs && idleMs >= restAfterPausedMs -> Stage.REST
        else -> Stage.ACTIVE
    }

    /** The same timeline run [factor] times faster, to watch it in a minute instead of half an hour. */
    fun faster(factor: Int): PresenceTimeline {
        val f = factor.coerceAtLeast(1)
        return PresenceTimeline(dimAfterMs / f, minimalAfterMs / f, blackAfterMs / f, blankAfterPausedMs / f, restAfterPausedMs / f)
    }

    companion object {
        /** DIM after three minutes (one song), MINIMAL after eight (two), BLACK after half an hour, BLANK after a quarter of an hour paused. */
        val STANDARD = PresenceTimeline(
            dimAfterMs = 3 * 60_000L,
            minimalAfterMs = 8 * 60_000L,
            blackAfterMs = 30 * 60_000L,
            blankAfterPausedMs = 15 * 60_000L,
        )
    }
}
