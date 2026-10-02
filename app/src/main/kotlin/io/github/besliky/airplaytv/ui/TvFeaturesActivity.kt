package io.github.besliky.airplaytv.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.lg.LgFacts
import io.github.besliky.airplaytv.lg.LgSetup
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
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
