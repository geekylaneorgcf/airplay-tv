package io.github.besliky.airplaytv.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.lg.LgFacts
import io.github.besliky.airplaytv.lg.LgSetup
import io.github.besliky.airplaytv.lg.TvPicture
import io.github.besliky.airplaytv.lg.TvScenes
import io.github.besliky.airplaytv.lg.TvSettings
import java.util.concurrent.Executors

/** What the receiver does with a paired LG TV, see [io.github.besliky.airplaytv.service.LgLink]. */
class TvFeaturesActivity : SubPage() {

    override val pageTitle = R.string.tv_features_title

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lg-setup").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var tvRow: Row
    private lateinit var wakeRow: Row
    private lateinit var inputRow: Row
    private lateinit var musicRow: Row
    private lateinit var pauseRow: Row
    private lateinit var volumeRow: Row
    private lateinit var panelRow: Row
    private lateinit var holdRow: Row
    private lateinit var noticesRow: Row
    private lateinit var autoRow: Row
    private lateinit var autoInputRow: Row
    private lateinit var autoModeRow: Row

    override fun buildRows() {
        tvRow = addRow(getString(R.string.tv_status)) {
            if (!TvSettings.paired(settings)) Toast.makeText(this, R.string.tv_needs_pairing, Toast.LENGTH_LONG).show()
        }
        wakeRow = addRow(getString(R.string.tv_wake)) {
            settings.tvWake = !settings.tvWake
            if (settings.tvWake) Dialogs.message(this, getString(R.string.tv_wake), getString(R.string.tv_wake_hint))
            bind()
        }
        inputRow = addRow(getString(R.string.tv_input)) { detectInput() }
        musicRow = addRow(getString(R.string.tv_music_mode)) {
            settings.musicMode = !settings.musicMode
            bind()
        }
        pauseRow = addRow(getString(R.string.tv_smart_pause)) {
            settings.smartPause = !settings.smartPause
            bind()
        }
        volumeRow = addRow(getString(R.string.tv_volume)) {
            settings.tvVolume = !settings.tvVolume
            bind()
        }
        panelRow = addRow(getString(R.string.tv_panel_double)) {
            settings.tvPanelDoublePress = !settings.tvPanelDoublePress
            if (settings.tvPanelDoublePress) Dialogs.message(this, getString(R.string.tv_panel_double), getString(R.string.tv_panel_double_hint))
            bind()
        }
        holdRow = addRow(getString(R.string.tv_hold)) {
            settings.tvHoldAction = (settings.tvHoldAction + 1) % HOLD_CHOICES
            if (settings.tvHoldAction != 0) Dialogs.message(this, getString(R.string.tv_hold), getString(R.string.tv_hold_hint))
            bind()
        }
        addRow(getString(R.string.tv_scenes)) {
            startActivity(Intent(this, TvScenesActivity::class.java))
        }.navigates(true)
        noticesRow = addRow(getString(R.string.tv_notices)) {
            settings.tvNotices = !settings.tvNotices
            bind()
        }
        autoRow = addRow(getString(R.string.tv_auto_picture)) {
            settings.tvAutoPicture = !settings.tvAutoPicture
            if (settings.tvAutoPicture) Dialogs.message(this, getString(R.string.tv_auto_picture), getString(R.string.tv_auto_picture_hint))
            bind()
        }
        autoInputRow = addRow(getString(R.string.tv_auto_input)) {
            settings.tvAutoPictureInput = next(AUTO_INPUTS, settings.tvAutoPictureInput)
            bind()
        }
        autoModeRow = addRow(getString(R.string.tv_auto_mode)) {
            settings.tvAutoPictureMode = next(TvPicture.MODES.map { it.id }, settings.tvAutoPictureMode)
            bind()
        }
        addRow(getString(R.string.tv_forget)) {
            settings.forgetTv()
            bind()
        }
    }

    private fun detectInput() {
        if (!TvSettings.paired(settings)) {
            Toast.makeText(this, R.string.tv_needs_pairing, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.tv_detecting, Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = LgSetup.learn(settings)
            main.post {
                if (isDestroyed) return@post
                val name = LgFacts.inputIdOf(result.input)?.replace('_', ' ')
                Toast.makeText(
                    this,
                    if (name != null) getString(R.string.tv_detected, name) else getString(R.string.tv_detect_failed),
                    Toast.LENGTH_LONG,
                ).show()
                bind()
            }
        }
    }

    override fun bind() {
        val paired = TvSettings.paired(settings)
        tvRow.value(getString(if (paired) R.string.value_tv_paired else R.string.value_tv_not_paired))
        wakeRow.value(onOff(settings.tvWake))
        inputRow.value(LgFacts.inputIdOf(settings.lgInput)?.replace('_', ' ') ?: getString(R.string.value_input_unknown))
        musicRow.value(onOff(settings.musicMode))
        pauseRow.value(onOff(settings.smartPause))
        volumeRow.value(onOff(settings.tvVolume))
        panelRow.value(onOff(settings.tvPanelDoublePress))
        holdRow.value(
            when (settings.tvHoldAction) {
                1 -> getString(R.string.value_hold_tv_off)
                2 -> getString(R.string.value_hold_panel)
                3 -> getString(R.string.value_hold_game)
                else -> getString(R.string.value_off)
            },
        )
        noticesRow.value(onOff(settings.tvNotices))
        autoRow.value(onOff(settings.tvAutoPicture))
        autoInputRow.value(TvScenes.inputLabel(settings.tvAutoPictureInput))
        autoModeRow.value(TvPicture.label(settings.tvAutoPictureMode))
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val HOLD_CHOICES = 4
        val AUTO_INPUTS = TvScenes.INPUT_CHOICES.filter { it.isNotEmpty() }
    }
}
