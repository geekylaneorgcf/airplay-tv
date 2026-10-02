package io.github.besliky.airplaytv.ui

import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings

/** The limits on how loud the receiver gets, see [io.github.besliky.airplaytv.service.VolumeLimits]. */
class VolumeActivity : SubPage() {

    override val pageTitle = R.string.volume_title

    private lateinit var maxRow: Row
    private lateinit var nightRow: Row
    private lateinit var fromRow: Row
    private lateinit var toRow: Row
    private lateinit var startRow: Row

    override fun buildRows() {
        maxRow = addRow(getString(R.string.volume_max)) {
            settings.maxVolumePercent = next(Settings.MAX_VOLUME_CHOICES, settings.maxVolumePercent)
            bind()
        }
        nightRow = addRow(getString(R.string.volume_night_max)) {
            settings.nightVolumePercent = next(Settings.NIGHT_VOLUME_CHOICES, settings.nightVolumePercent)
            bind()
        }
        fromRow = addRow(getString(R.string.volume_night_from)) {
            settings.nightFromHour = next(Settings.NIGHT_FROM_CHOICES, settings.nightFromHour)
            bind()
        }
        toRow = addRow(getString(R.string.volume_night_to)) {
            settings.nightToHour = next(Settings.NIGHT_TO_CHOICES, settings.nightToHour)
            bind()
        }
        startRow = addRow(getString(R.string.volume_start)) {
            settings.startVolumePercent = next(Settings.START_VOLUME_CHOICES, settings.startVolumePercent)
            bind()
        }
    }

    override fun bind() {
        maxRow.value(if (settings.maxVolumePercent >= 100) getString(R.string.value_no_limit) else percent(settings.maxVolumePercent))
        nightRow.value(percent(settings.nightVolumePercent, none = settings.nightVolumePercent == 0))
        val night = settings.nightVolumePercent > 0
        fromRow.value(hour(settings.nightFromHour)).visible(night)
        toRow.value(hour(settings.nightToHour)).visible(night)
        startRow.value(percent(settings.startVolumePercent, none = settings.startVolumePercent == 0))
    }
}
