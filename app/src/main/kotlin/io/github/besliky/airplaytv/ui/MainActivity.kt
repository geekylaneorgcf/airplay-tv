package io.github.besliky.airplaytv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.service.ReceiverService
import io.github.besliky.airplaytv.service.ReceiverState
import io.github.besliky.airplaytv.service.ReceiverState.Status
import io.github.besliky.airplaytv.service.SleepService

/** Home screen: receiver status and the basic settings. */
class MainActivity : SettingsPage() {

    private lateinit var settings: Settings
    private lateinit var nameRow: Row
    private lateinit var enabledRow: Row
    private lateinit var pinRow: Row
    private lateinit var autostartRow: Row
    private lateinit var infoRow: Row
    private lateinit var autoOpenRow: Row
    private lateinit var lyricsRow: Row
    private lateinit var sleepRow: Row

    private val stateListener: (ReceiverState.Snapshot) -> Unit = { render(it) }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> bindRows() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        nameRow = addRow(getString(R.string.setting_device_name)) {
            Dialogs.editName(this, settings.deviceName) { name -> settings.deviceName = name }
        }
        enabledRow = addRow(getString(R.string.setting_airplay)) {
            settings.enabled = !settings.enabled
            if (settings.enabled) ReceiverService.start(this)
        }
        pinRow = addRow(getString(R.string.setting_require_pin)) { settings.requirePin = !settings.requirePin }
        autostartRow = addRow(getString(R.string.setting_autostart)) {
            settings.startAutomatically = !settings.startAutomatically
        }
        infoRow = addRow(getString(R.string.setting_connection_info)) {
            settings.showConnectionInfo = !settings.showConnectionInfo
            render(ReceiverState.current)
        }
        autoOpenRow = addRow(getString(R.string.setting_auto_open)) { requestAutoOpen() }
        lyricsRow = addRow(getString(R.string.setting_lyrics)) { toggleLyrics() }
        sleepRow = addRow(getString(R.string.setting_sleep)) { cycleSleep() }
        addRow(getString(R.string.setting_advanced)) {
            startActivity(Intent(this, AdvancedActivity::class.java))
        }.navigates(true)

        bindRows()
        rows.getChildAt(0)?.requestFocus()
        requestNotificationPermissionOnce()
        showPreviewIfRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showPreviewIfRequested(intent)
    }

    /** Development aid: `--es preview now` shows the Now Playing screen with sample data, see [PreviewMode]. */
    private fun showPreviewIfRequested(intent: Intent?) {
        if (intent == null) return
        val mode = intent.getStringExtra(NowPlayingActivity.EXTRA_PREVIEW) ?: return
        intent.removeExtra(NowPlayingActivity.EXTRA_PREVIEW)
        startActivity(Intent(this, NowPlayingActivity::class.java).putExtra(NowPlayingActivity.EXTRA_PREVIEW, mode))
    }

    private fun sleepValue(): String {
        val minutes = settings.sleepAfterMinutes
        return when {
            minutes == 0 -> getString(R.string.value_off)
            !SleepService.isEnabled -> getString(R.string.value_needs_setup)
            else -> getString(R.string.value_minutes, minutes)
        }
    }

    private fun cycleSleep() {
        val choices = Settings.SLEEP_CHOICES
        val next = choices[(choices.indexOf(settings.sleepAfterMinutes) + 1) % choices.size]
        settings.sleepAfterMinutes = next
        if (next > 0 && !SleepService.isEnabled) {
            val component = "$packageName/${SleepService::class.java.name}"
            Dialogs.message(this, getString(R.string.dialog_sleep_title), getString(R.string.dialog_sleep_message, component))
        }
    }

    private fun toggleLyrics() {
        settings.lyricsEnabled = !settings.lyricsEnabled
        if (settings.lyricsEnabled) {
            Dialogs.message(this, getString(R.string.dialog_lyrics_title), getString(R.string.dialog_lyrics_message))
        }
    }

    override fun onStart() {
        super.onStart()
        if (settings.enabled) ReceiverService.start(this)
        settings.registerListener(prefsListener)
        ReceiverState.observe(stateListener)
        bindRows()
    }

    override fun onStop() {
        ReceiverState.remove(stateListener)
        settings.unregisterListener(prefsListener)
        super.onStop()
    }

    private fun canOpenAutomatically(): Boolean =
        Build.VERSION.SDK_INT < 29 || SystemSettings.canDrawOverlays(this)

    private fun bindRows() {
        nameRow.value(settings.deviceName)
        enabledRow.value(onOff(settings.enabled))
        pinRow.value(onOff(settings.requirePin))
        autostartRow.value(onOff(settings.startAutomatically))
        lyricsRow.value(onOff(settings.lyricsEnabled))
        sleepRow.value(sleepValue())
        infoRow.value(onOff(settings.showConnectionInfo))
        autoOpenRow.visible(Build.VERSION.SDK_INT >= 29)
            .value(getString(if (canOpenAutomatically()) R.string.value_allowed else R.string.value_allow))
    }

    private fun render(s: ReceiverState.Snapshot) {
        val enabled = settings.enabled
        val status = if (!enabled) Status.OFF else s.status
        statusView.text = getString(
            when (status) {
                Status.OFF -> R.string.status_off
                Status.STARTING -> R.string.status_starting
                Status.READY -> R.string.status_ready
                Status.NO_NETWORK -> R.string.status_no_network
                Status.ERROR -> R.string.status_error
                Status.CONNECTED -> R.string.status_connected
            }
        )
        statusDot.background.mutate().setTint(
            getColor(
                when (status) {
                    Status.READY, Status.CONNECTED -> R.color.status_ready
                    Status.OFF -> R.color.text_secondary
                    else -> R.color.status_warning
                }
            )
        )
        nameView.text = s.publishedName.ifEmpty { settings.deviceName }
        val address = s.addresses.firstOrNull()
        addressView.text = if (settings.showConnectionInfo && address != null && enabled) address else ""
        addressView.visibility = if (addressView.text.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        hintView.text = when (status) {
            Status.OFF -> getString(R.string.hint_off)
            Status.NO_NETWORK -> getString(R.string.hint_no_network)
            Status.ERROR -> getString(R.string.hint_error)
            Status.CONNECTED -> getString(R.string.hint_mirroring, s.clientName ?: "iPhone")
            else -> getString(R.string.hint_waiting)
        }
    }

    private fun requestAutoOpen() {
        if (canOpenAutomatically()) return
        val intent = Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Dialogs.message(this, getString(R.string.dialog_overlay_title), getString(R.string.dialog_overlay_message, packageName))
        } catch (_: SecurityException) {
            Dialogs.message(this, getString(R.string.dialog_overlay_title), getString(R.string.dialog_overlay_message, packageName))
        }
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("notification_asked", false)) return
        prefs.edit().putBoolean("notification_asked", true).apply()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
