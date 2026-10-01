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
    private lateinit var appearanceRow: Row

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
        appearanceRow = addRow(getString(R.string.setting_appearance)) { cycleAppearance() }
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
        pairedRow.value(paired.count.toString())
        appearanceRow.value(appearanceName(settings.appearance))
    }

    private fun appearanceName(appearance: Settings.Appearance): String = getString(
        when (appearance) {
            Settings.Appearance.APPLE_TV -> R.string.appearance_apple_tv
            Settings.Appearance.APPLE_TV_4K -> R.string.appearance_apple_tv_4k
            Settings.Appearance.TV -> R.string.appearance_tv
        },
    )

    private fun cycleAppearance() {
        settings.appearance = settings.appearance.next()
        bind()
        if (settings.appearance != Settings.Appearance.APPLE_TV) {
            Dialogs.message(this, getString(R.string.dialog_appearance_title), getString(R.string.dialog_appearance_message))
        }
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
