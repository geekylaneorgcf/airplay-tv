package io.github.besliky.airplaytv.ui

import io.github.besliky.airplaytv.R

/** Bass, treble, loudness and night mode for the music, see [io.github.besliky.airplaytv.service.SoundEffects]. */
class SoundActivity : SubPage() {

    override val pageTitle = R.string.sound_title

    private lateinit var bassRow: Row
    private lateinit var trebleRow: Row
    private lateinit var loudnessRow: Row
    private lateinit var nightRow: Row

    private val steps = listOf(0, 1, 2, 3, -3, -2, -1)

    override fun buildRows() {
        bassRow = addRow(getString(R.string.sound_bass)) {
            settings.bass = next(steps, settings.bass)
            bind()
        }
        trebleRow = addRow(getString(R.string.sound_treble)) {
            settings.treble = next(steps, settings.treble)
            bind()
        }
        loudnessRow = addRow(getString(R.string.sound_loudness)) {
            settings.loudness = (settings.loudness + 1) % 4
            bind()
        }
        nightRow = addRow(getString(R.string.sound_night)) {
            settings.nightMode = !settings.nightMode
            bind()
        }
    }

    private fun step(value: Int): String = if (value == 0) getString(R.string.value_flat) else getString(R.string.value_step, value)

    override fun bind() {
        bassRow.value(step(settings.bass))
        trebleRow.value(step(settings.treble))
        loudnessRow.value(
            when (settings.loudness) {
                1 -> getString(R.string.value_loudness_low)
                2 -> getString(R.string.value_loudness_medium)
                3 -> getString(R.string.value_loudness_high)
                else -> getString(R.string.value_off)
            },
        )
        nightRow.value(onOff(settings.nightMode))
    }
}
