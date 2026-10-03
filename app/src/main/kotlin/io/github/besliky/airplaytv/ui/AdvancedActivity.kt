package io.github.besliky.airplaytv.ui

import android.os.Bundle
import android.view.View
import io.github.besliky.airplaytv.BuildConfig
import io.github.besliky.airplaytv.Diagnostics
import io.github.besliky.airplaytv.Identity
import io.github.besliky.airplaytv.PairedDevices
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.core.DecoderSelector
import io.github.besliky.airplaytv.service.ReceiverService
import io.github.besliky.airplaytv.service.SelfCheck

/** Advanced settings and maintenance actions. */
class AdvancedActivity : SettingsPage() {

    private lateinit var settings: Settings
    private lateinit var paired: PairedDevices
    private lateinit var decoderRow: Row
    private lateinit var resolutionRow: Row
    private lateinit var fpsRow: Row
    private lateinit var overlayRow: Row
    private lateinit var logsRow: Row
    private lateinit var pairedRow: Row
    private lateinit var appearRow: Row
    private lateinit var videoRow: Row
    private lateinit var airplay2Row: Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        paired = PairedDevices(this)

        titleView.text = getString(R.string.advanced_title)
        statusRow.visibility = View.GONE
        nameView.visibility = View.GONE
        addressView.visibility = View.GONE
        hintView.visibility = View.GONE

        decoderRow = addRow(getString(R.string.setting_decoder)) { chooseDecoder() }.navigates(true)
        resolutionRow = addRow(getString(R.string.setting_resolution)) { cycleResolution() }
        fpsRow = addRow(getString(R.string.setting_frame_rate)) {
            settings.frameRate = if (settings.frameRate == 60) 30 else 60
            bind()
        }
        overlayRow = addRow(getString(R.string.setting_overlay)) {
            settings.performanceOverlay = !settings.performanceOverlay
            bind()
        }
        logsRow = addRow(getString(R.string.setting_logs)) {
            settings.verboseLogging = !settings.verboseLogging
            bind()
        }
        videoRow = addRow(getString(R.string.setting_airplay_video)) {
            settings.airplayVideo = !settings.airplayVideo
            bind()
        }
        if (BuildConfig.DEBUG) {
            airplay2Row = addRow(getString(R.string.setting_airplay2)) { toggleAirplay2() }
        }
        addRow(getString(R.string.setting_check)) { runCheck() }.navigates(true)
        appearRow = addRow(getString(R.string.setting_appear)) { toggleAppearance() }
        addRow(getString(R.string.setting_diagnostics)) { exportDiagnostics() }.navigates(true)
        pairedRow = addRow(getString(R.string.setting_paired_devices)) { showPairedDevices() }.navigates(true)
        addRow(getString(R.string.setting_reset_pairing)) { resetPairing() }
        addRow(getString(R.string.setting_licenses)) {
            Dialogs.longText(this, getString(R.string.setting_licenses), Diagnostics.licenses(this))
        }.navigates(true)
        addRow(getString(R.string.setting_version)) { }.value(BuildConfig.VERSION_NAME)

        bind()
        rows.getChildAt(0)?.requestFocus()
    }

    private fun bind() {
        decoderRow.value(settings.preferredDecoder.ifEmpty { getString(R.string.value_automatic) })
        resolutionRow.value(settings.resolution.key)
        fpsRow.value(getString(R.string.value_fps, settings.frameRate))
        overlayRow.value(onOff(settings.performanceOverlay))
        logsRow.value(onOff(settings.verboseLogging))
        videoRow.value(onOff(settings.airplayVideo))
        if (BuildConfig.DEBUG) {
            airplay2Row.value(
                when {
                    settings.airplay2 == Settings.AIRPLAY2_OFF -> getString(R.string.value_off)
                    settings.airplay2TrialUntil > 0L -> getString(R.string.value_airplay2_trial)
                    settings.airplay2 == Settings.AIRPLAY2_FULL -> getString(R.string.value_airplay2_full)
                    else -> getString(R.string.value_on)
                },
            )
        }
        pairedRow.value(paired.count.toString())
        appearRow.value(
            when {
                !settings.speakerMode -> getString(R.string.value_appear_apple_tv)
                settings.speakerTrialUntil > 0L -> getString(R.string.value_appear_speaker_trial)
                else -> getString(R.string.value_appear_speaker)
            },
        )
    }

    /** Asks the receiver about itself and says, in plain words, why a phone might not see this TV. */
    private fun runCheck() {
        Thread {
            val findings = SelfCheck.evaluate(SelfCheck.gather(this, settings))
            runOnUiThread {
                if (!isDestroyed) Dialogs.message(this, getString(R.string.check_title), SelfCheck.text(findings))
            }
        }.start()
    }

    /** Apple TV is the default and works with everything; the speaker is a trial that undoes itself, see [Appearance]. */
    private fun toggleAppearance() {
        if (settings.speakerMode) {
            settings.speakerTrialUntil = 0L
            settings.speakerMode = false
            bind()
            return
        }
        Dialogs.confirm(this, R.string.dialog_appear_title, R.string.dialog_appear_message, R.string.dialog_appear_confirm) {
            settings.speakerTrialUntil = System.currentTimeMillis() + SPEAKER_TRIAL_MS
            settings.speakerMode = true
            bind()
        }
    }

    /** AirPlay 2 for music is a trial that undoes itself (see [Settings.airplay2]): off, then on, then on with the timing bits. */
    private fun toggleAirplay2() {
        when (settings.airplay2) {
            Settings.AIRPLAY2_OFF -> Dialogs.confirm(this, R.string.dialog_airplay2_title, R.string.dialog_airplay2_message, R.string.dialog_airplay2_confirm) {
                settings.airplay2TrialUntil = System.currentTimeMillis() + AIRPLAY2_TRIAL_MS
                settings.airplay2 = Settings.AIRPLAY2_ON
                bind()
            }
            Settings.AIRPLAY2_ON -> {
                settings.airplay2TrialUntil = System.currentTimeMillis() + AIRPLAY2_TRIAL_MS
                settings.airplay2 = Settings.AIRPLAY2_FULL
                bind()
            }
            else -> {
                settings.airplay2TrialUntil = 0L
                settings.airplay2 = Settings.AIRPLAY2_OFF
                bind()
            }
        }
    }

    private companion object {
        /** How long an AirPlay 2 trial waits for a phone to start a session before it switches itself off. */
        const val AIRPLAY2_TRIAL_MS = 30 * 60 * 1000L

        /** How long a speaker trial waits for music before it switches itself back. */
        const val SPEAKER_TRIAL_MS = 30 * 60 * 1000L
    }

    private fun chooseDecoder() {
        val decoders = DecoderSelector.candidates(DecoderSelector.AVC)
        val usable = decoders.filter { it.hardware }.ifEmpty { decoders }
        val names = listOf(getString(R.string.value_automatic)) + usable.map { d ->
            d.name + if (d.lowLatency) "  ·  low latency" else ""
        }
        val selected = usable.indexOfFirst { it.name == settings.preferredDecoder }.let { if (it < 0) 0 else it + 1 }
        Dialogs.choose(this, R.string.dialog_decoder_title, names, selected) { which ->
            settings.preferredDecoder = if (which == 0) "" else usable[which - 1].name
            bind()
        }
    }

    private fun cycleResolution() {
        val next = when (settings.resolution) {
            Settings.Resolution.HD -> Settings.Resolution.FULL_HD
            Settings.Resolution.FULL_HD -> Settings.Resolution.UHD
            Settings.Resolution.UHD -> Settings.Resolution.HD
        }
        if (next == Settings.Resolution.UHD && !DecoderSelector.select(null).hevc4k) {
            settings.resolution = Settings.Resolution.HD
            Dialogs.message(this, getString(R.string.setting_resolution), getString(R.string.value_4k_unavailable))
        } else {
            settings.resolution = next
        }
        bind()
    }

    private fun showPairedDevices() {
        val devices = paired.all()
        val text = if (devices.isEmpty()) "—" else devices.joinToString("\n") { it.name.ifEmpty { "iPhone" } }
        Dialogs.message(this, getString(R.string.setting_paired_devices), text)
    }

    private fun resetPairing() {
        Dialogs.confirm(this, R.string.dialog_reset_title, R.string.dialog_reset_message, R.string.dialog_reset_confirm) {
            paired.clear()
            Identity.resetPairingKey(this)
            ReceiverService.restart(this)
            bind()
            Dialogs.message(this, getString(R.string.setting_reset_pairing), getString(R.string.dialog_reset_done))
        }
    }

    private fun exportDiagnostics() {
        val report = Diagnostics.build(this)
        val file = Diagnostics.save(this, report)
        val header = if (file != null) getString(R.string.dialog_diagnostics_saved, file.absolutePath) + "\n\n" else ""
        Dialogs.longText(this, getString(R.string.dialog_diagnostics_title), header + report)
    }
}
