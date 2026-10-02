package io.github.besliky.airplaytv.service

import android.annotation.TargetApi
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.AUDIO
import io.github.besliky.airplaytv.Settings

/**
 * Bass and treble, a loudness boost and a night mode that evens out loud and quiet parts, applied to the track the music plays
 * through with the system's own audio effects. The TV's speakers are small, and these are what a small speaker needs.
 * Whether a device carries out an effect on its HDMI output is up to the device: each one is tried on its own, and one that cannot
 * be had is logged and left out, never an error for the music.
 */
class SoundEffects(private val settings: Settings) {

    private var sessionId = 0
    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var dynamics: DynamicsProcessing? = null

    /** The music now plays through the audio session [audioSessionId]: put the effects on it. */
    fun attach(audioSessionId: Int) {
        release()
        sessionId = audioSessionId
        apply()
    }

    /** Applies the settings as they are now (they may have changed while the music plays). */
    fun apply() {
        if (sessionId == 0) return
        applyEqualizer()
        applyLoudness()
        applyNightMode()
    }

    fun release() {
        for (effect in listOf(equalizer, loudness, dynamics)) {
            try {
                effect?.release()
            } catch (_: RuntimeException) {
                // gone already
            }
        }
        equalizer = null
        loudness = null
        dynamics = null
        sessionId = 0
    }

    private fun applyEqualizer() {
        val bass = settings.bass
        val treble = settings.treble
        if (bass == 0 && treble == 0) {
            equalizer?.enabled = false
            return
        }
        try {
            val eq = equalizer ?: Equalizer(0, sessionId).also { equalizer = it }
            val range = eq.bandLevelRange
            val centres = IntArray(eq.numberOfBands.toInt()) { eq.getCenterFreq(it.toShort()) / 1000 }
            val levels = SoundPlan.bandLevels(centres, range[0].toInt(), range[1].toInt(), bass, treble)
            for (band in levels.indices) eq.setBandLevel(band.toShort(), levels[band].toShort())
            eq.enabled = true
        } catch (e: Exception) {
            Log.w(AUDIO, "no equalizer for the music", e)
            equalizer = null
        }
    }

    private fun applyLoudness() {
        val step = settings.loudness
        if (step == 0) {
            loudness?.enabled = false
            return
        }
        try {
            val enhancer = loudness ?: LoudnessEnhancer(sessionId).also { loudness = it }
            enhancer.setTargetGain(SoundPlan.loudnessGainMb(step))
            enhancer.enabled = true
        } catch (e: Exception) {
            Log.w(AUDIO, "no loudness boost for the music", e)
            loudness = null
        }
    }

    /** Night mode: a compressor that brings the loud parts down and the quiet parts up, and a limiter behind it. */
    private fun applyNightMode() {
        if (!settings.nightMode) {
            dynamics?.enabled = false
            return
        }
        if (Build.VERSION.SDK_INT < 28) return
        try {
            val processing = dynamics ?: build().also { dynamics = it }
            processing.enabled = true
        } catch (e: Throwable) {
            Log.w(AUDIO, "no night mode for the music", e)
            dynamics = null
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun build(): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS, false, 0, true, 1, false, 0, true,
        ).build()
        val processing = DynamicsProcessing(0, sessionId, config)
        for (channel in 0 until CHANNELS) {
            val band = processing.getMbcBandByChannelIndex(channel, 0)
            band.isEnabled = true
            band.cutoffFrequency = 20_000f
            band.attackTime = 12f
            band.releaseTime = 250f
            band.ratio = SoundPlan.NIGHT_RATIO
            band.threshold = SoundPlan.NIGHT_THRESHOLD_DB
            band.kneeWidth = 8f
            band.noiseGateThreshold = -90f
            band.expanderRatio = 1f
            band.preGain = 0f
            band.postGain = SoundPlan.NIGHT_MAKEUP_DB
            processing.setMbcBandByChannelIndex(channel, 0, band)
            val limiter = processing.getLimiterByChannelIndex(channel)
            limiter.isEnabled = true
            limiter.attackTime = 1f
            limiter.releaseTime = 60f
            limiter.ratio = 12f
            limiter.threshold = -2f
            processing.setLimiterByChannelIndex(channel, limiter)
        }
        return processing
    }

    private companion object {
        const val CHANNELS = 2
    }
}

/** What the sound settings mean in numbers, kept apart from the system's effects so it can be tested. */
object SoundPlan {

    /** Three decibels (300 millibels) a step. */
    private const val STEP_MB = 300

    /** The compressor of night mode: four to one above this level, and the gain that makes up for what it takes off. */
    const val NIGHT_RATIO = 4f
    const val NIGHT_THRESHOLD_DB = -30f
    const val NIGHT_MAKEUP_DB = 8f

    /** The loudness boost in millibels for a step from 1 to 3. */
    fun loudnessGainMb(step: Int): Int = when (step.coerceIn(0, 3)) {
        0 -> 0
        1 -> 300
        2 -> 600
        else -> 900
    }

    /**
     * The level in millibels for each band of an equalizer whose bands are centred on [centresHz] and which takes levels from [minMb]
     * to [maxMb]: bass lifts (or lowers) what is under 250 Hz and fades out by 1 kHz, treble what is over 4 kHz and fades out by
     * 1 kHz. Each step is three decibels.
     */
    fun bandLevels(centresHz: IntArray, minMb: Int, maxMb: Int, bassSteps: Int, trebleSteps: Int): IntArray =
        IntArray(centresHz.size) { i ->
            val hz = centresHz[i]
            val bass = when {
                hz <= 250 -> 1f
                hz >= 1000 -> 0f
                else -> (1000 - hz) / 750f
            }
            val treble = when {
                hz >= 4000 -> 1f
                hz <= 1000 -> 0f
                else -> (hz - 1000) / 3000f
            }
            Math.round(bassSteps * STEP_MB * bass + trebleSteps * STEP_MB * treble).coerceIn(minMb, maxMb)
        }
}
