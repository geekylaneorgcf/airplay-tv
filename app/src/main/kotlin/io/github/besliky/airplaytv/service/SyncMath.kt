package io.github.besliky.airplaytv.service

import kotlin.math.abs

/**
 * Lines the lyrics up with what is heard. The sender reports where it is in the song, but what comes out of the
 * speakers lags that by an amount that depends on the sender and on this device's buffers. The receiver knows the
 * position of the newest packet it received and how much audio is still queued, so it can tell where the audible
 * sound is and how far that is from the position the sender reported.
 */
object SyncMath {

    /** A packet older than this means the audio supply has stopped, and its position says nothing about now. */
    private const val MAX_AGE_MS = 1_000L

    /** An offset larger than this is a measuring error (a seek, a stale packet), not a delay. */
    private const val MAX_OFFSET_MS = 4_000L

    private const val MAX_PLAUSIBLE_MS = 6L * 3600 * 1000

    /**
     * How much later in the song the sound being heard is than the position the sender reported ([reportedMs]), in
     * milliseconds (negative when it is earlier), or null when it cannot be told.
     *
     * [startRtp] is the song's first RTP timestamp, [lastRtp] the newest packet's, [ageMs] how long ago it arrived,
     * [queuedMs] the audio decoded but not yet played (the receiver's ring and the output buffer) and [rate] the
     * sample rate.
     */
    fun offsetMs(startRtp: Long, reportedMs: Long, lastRtp: Long, ageMs: Long, queuedMs: Long, rate: Long): Long? {
        if (rate <= 0 || ageMs < 0 || ageMs > MAX_AGE_MS) return null
        val delivered = ((lastRtp - startRtp) and 0xFFFFFFFFL) * 1000 / rate
        if (delivered > MAX_PLAUSIBLE_MS) return null
        val audible = delivered - queuedMs + ageMs
        val offset = audible - reportedMs
        return offset.takeIf { abs(it) <= MAX_OFFSET_MS }
    }

    /** The middle value, so that one odd measurement does not move the lyrics. */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
