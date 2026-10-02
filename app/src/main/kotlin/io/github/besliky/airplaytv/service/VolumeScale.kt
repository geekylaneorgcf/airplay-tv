package io.github.besliky.airplaytv.service

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** The AirPlay volume scale: -30 dB at the bottom of the slider, 0 dB at the top, silence below. */
object VolumeScale {

    private const val BOTTOM_DB = -30f
    private const val SILENT_DB = -144f

    /** The sender's gain (linear, 0 is mute) as a slider position from 0 to 1. */
    fun gainToLevel(gain: Float): Float {
        val db = if (gain <= 0f) SILENT_DB else 20f * log10(gain)
        return ((db - BOTTOM_DB) / -BOTTOM_DB).coerceIn(0f, 1f)
    }

    /** A slider position as the linear gain the audio output takes; 0 is silence. */
    fun levelToGain(level: Float): Float =
        if (level <= 0f) 0f else 10f.pow((level - 1f) * -BOTTOM_DB / 20f)

    /**
     * True when two reported gains are the same setting, to within a twentieth of a dB. The sender's
     * slider moves in steps of nearly 2 dB, so equal means "reported again", not "moved".
     */
    fun sameGain(a: Float, b: Float): Boolean {
        if (a <= 0f || b <= 0f) return a <= 0f && b <= 0f
        return abs(20f * log10(a / b)) < 0.05f
    }

    /** The slider position [delta] steps from [level], on a slider of [steps] steps, held between 0 and 1. */
    fun stepped(level: Float, delta: Int, steps: Int): Float =
        (Math.round(level * steps) + delta).coerceIn(0, steps) / steps.toFloat()
}
