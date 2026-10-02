package io.github.besliky.airplaytv.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.KeyEvent
import io.github.besliky.airplaytv.BuildConfig
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.WhatsNew
import io.github.besliky.airplaytv.lg.TvMenuMode
import io.github.besliky.airplaytv.lg.TvSettings
import io.github.besliky.airplaytv.service.AccessibilitySetup
import io.github.besliky.airplaytv.service.MenuKeyService
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
    private lateinit var ytmRow: Row
    private lateinit var timingRow: Row
    private lateinit var sleepRow: Row
    private lateinit var returnRow: Row
    private lateinit var tvMenuRow: Row
    private lateinit var visualizerRow: Row
    private lateinit var takeoverRow: Row

    private val stateListener: (ReceiverState.Snapshot) -> Unit = { render(it) }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> bindRows() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returnTarget(intent)?.let {
            openPlayer(it)
            return
        }
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
        ytmRow = addRow(getString(R.string.setting_lyrics_ytm)) { toggleYoutubeMusicLyrics() }
        timingRow = addRow(getString(R.string.setting_lyrics_timing)) { cycleLyricsTiming() }
        sleepRow = addRow(getString(R.string.setting_sleep)) { cycleSleep() }
        returnRow = addRow(getString(R.string.setting_return)) { toggleReturn() }
        tvMenuRow = addRow(getString(R.string.setting_tv_menu)) { configureTvMenu() }
        addRow(getString(R.string.setting_tv_features)) {
            startActivity(Intent(this, TvFeaturesActivity::class.java))
        }.navigates(true)
        addRow(getString(R.string.setting_volume)) {
            startActivity(Intent(this, VolumeActivity::class.java))
        }.navigates(true)
        addRow(getString(R.string.setting_sound)) {
            startActivity(Intent(this, SoundActivity::class.java))
        }.navigates(true)
        visualizerRow = addRow(getString(R.string.visualizer_setting)) { settings.visualizer = !settings.visualizer }
        takeoverRow = addRow(getString(R.string.setting_takeover)) {
            val choices = Settings.TAKEOVER_CHOICES
            settings.takeover = choices[(choices.indexOf(settings.takeover) + 1) % choices.size]
        }
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
        returnTarget(intent)?.let {
            openPlayer(it)
            return
        }
        showPreviewIfRequested(intent)
    }

    /**
     * Starting the app from the Home screen while a sender plays means "show me what is playing", so go straight to
     * the player (for audio) or to the picture (for screen mirroring and video). The player's Menu key opens this page
     * with [EXTRA_STAY] set. The home screen's "AirPlay" chip starts the app the same way.
     */
    private fun returnTarget(intent: Intent?): Class<out Activity>? {
        if (intent == null || intent.action != Intent.ACTION_MAIN) return null
        if (intent.getBooleanExtra(EXTRA_STAY, false) || intent.hasExtra(NowPlayingActivity.EXTRA_PREVIEW)) return null
        val s = ReceiverState.current
        if (s.status != Status.CONNECTED) return null
        return when {
            s.videoActive -> MirrorActivity::class.java
            s.audioActive -> NowPlayingActivity::class.java
            else -> null
        }
    }

    private fun openPlayer(target: Class<out Activity>) {
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /** Development aid: `--es preview now` shows the Now Playing screen with sample data, see [PreviewMode]. */
    private fun showPreviewIfRequested(intent: Intent?) {
        if (intent == null) return
        val mode = intent.getStringExtra(NowPlayingActivity.EXTRA_PREVIEW) ?: return
        intent.removeExtra(NowPlayingActivity.EXTRA_PREVIEW)
        when (mode) {
            "dialog-sleep" -> return showSleepSetup()
            "dialog-overlay" -> return showOverlaySetup()
            "dialog-lyrics" -> return Dialogs.message(this, getString(R.string.dialog_lyrics_title), getString(R.string.dialog_lyrics_message))
            "dialog-reset" -> return Dialogs.confirm(this, R.string.dialog_reset_title, R.string.dialog_reset_message, R.string.dialog_reset_confirm) {}
            "dither" -> return DitherPreview.show(this)
        }
        val target = if (mode.startsWith("photo")) PhotoActivity::class.java else NowPlayingActivity::class.java
        val forward = Intent(this, target).putExtra(NowPlayingActivity.EXTRA_PREVIEW, mode)
        if (intent.hasExtra(NowPlayingActivity.EXTRA_SPEED)) {
            forward.putExtra(NowPlayingActivity.EXTRA_SPEED, intent.getIntExtra(NowPlayingActivity.EXTRA_SPEED, 60))
        }
        startActivity(forward)
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
        if (next > 0 && !SleepService.isEnabled) showSleepSetup()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // after "Open TV Settings Now" the arrow keys, OK and Back steer the TV's menu
        if (TvMenuMode.isActive && TvMenuMode.onKey(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun cycleLyricsTiming() {
        val choices = Settings.LYRICS_OFFSET_CHOICES
        settings.lyricsOffsetMs = choices[(choices.indexOf(settings.lyricsOffsetMs) + 1) % choices.size]
    }

    private fun lyricsTimingValue(): String {
        val ms = settings.lyricsOffsetMs
        if (ms == 0) return getString(R.string.value_automatic)
        val seconds = Math.abs(ms) / 1000.0
        return getString(if (ms > 0) R.string.value_lyrics_earlier else R.string.value_lyrics_later, seconds.toString())
    }

    private fun showSleepSetup() {
        Dialogs.steps(
            this,
            getString(R.string.dialog_sleep_title),
            getString(R.string.dialog_sleep_intro),
            AccessibilitySetup.commands(this, MenuKeyService.component(packageName)),
            getString(R.string.dialog_sleep_outro),
        )
    }

    private fun showOverlaySetup() {
        Dialogs.steps(
            this,
            getString(R.string.dialog_overlay_title),
            getString(R.string.dialog_overlay_intro),
            listOf("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"),
        )
    }

    private fun tvMenuValue(): String = when (TvSettings.state(settings)) {
        TvSettings.State.OFF -> getString(R.string.value_off)
        TvSettings.State.NEEDS_SETUP -> getString(R.string.value_needs_setup)
        TvSettings.State.READY ->
            getString(if (MenuKeyService.isEnabled) R.string.value_tv_everywhere else R.string.value_tv_player)
    }

    /**
     * Off: set the button up. Set up: a list of things to do, none of them a choice that stays selected: try it, see
     * (or switch on) the button in every app, set it up again, turn it off.
     */
    private fun configureTvMenu() {
        if (TvSettings.state(settings) != TvSettings.State.READY) {
            TvPairing.start(this, settings) { bindRows() }
            return
        }
        val everywhere = MenuKeyService.isEnabled
        val items = listOf(
            getString(R.string.tv_menu_try),
            getString(if (everywhere) R.string.tv_menu_every_app_on else R.string.tv_menu_every_app_off),
            getString(R.string.tv_menu_pair_again),
            getString(R.string.tv_menu_turn_off),
        )
        Dialogs.actions(this, R.string.tv_menu_title, items) { index ->
            when (index) {
                0 -> TvMenuMode.toggle(this)
                1 -> if (everywhere) {
                    Dialogs.message(this, getString(R.string.tv_menu_everywhere_title), getString(R.string.tv_menu_everywhere_message))
                } else {
                    TvPairing.showEveryAppSetup(this)
                }
                2 -> {
                    settings.forgetTv()
                    TvPairing.start(this, settings) { bindRows() }
                }
                3 -> {
                    settings.tvMenuButton = false
                    bindRows()
                }
            }
        }
    }

    private fun toggleReturn() {
        settings.returnToPlayer = !settings.returnToPlayer
        if (settings.returnToPlayer) {
            Dialogs.message(this, getString(R.string.dialog_return_title), getString(R.string.dialog_return_message))
        }
    }

    private fun toggleYoutubeMusicLyrics() {
        settings.lyricsYoutubeMusic = !settings.lyricsYoutubeMusic
        if (settings.lyricsYoutubeMusic) {
            Dialogs.message(this, getString(R.string.dialog_lyrics_ytm_title), getString(R.string.dialog_lyrics_ytm_message))
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
        if (!::settings.isInitialized) return // forwarded to the player in onCreate
        if (settings.enabled) ReceiverService.start(this)
        settings.registerListener(prefsListener)
        ReceiverState.observe(stateListener)
        bindRows()
        showWhatsNewOnce()
    }

    /** After an update, once: the few plain lines in `res/raw/whats_new.txt`. */
    private fun showWhatsNewOnce() {
        val lines = WhatsNew.parse(
            try {
                resources.openRawResource(R.raw.whats_new).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                null
            },
        )
        if (!WhatsNew.shouldShow(settings.seenVersion, BuildConfig.VERSION_NAME, lines)) return
        settings.seenVersion = BuildConfig.VERSION_NAME
        Dialogs.message(this, getString(R.string.whats_new_title, BuildConfig.VERSION_NAME), lines!!.joinToString("\n\n"))
    }

    override fun onStop() {
        if (::settings.isInitialized) {
            ReceiverState.remove(stateListener)
            settings.unregisterListener(prefsListener)
        }
        super.onStop()
    }

    companion object {
        /** Set when the page is opened from the player's Menu key, so that it is not forwarded back to the player. */
        const val EXTRA_STAY = "stay"
    }

    private fun canOpenAutomatically(): Boolean =
        Build.VERSION.SDK_INT < 29 || SystemSettings.canDrawOverlays(this)

    private fun bindRows() {
        nameRow.value(settings.deviceName)
        enabledRow.value(onOff(settings.enabled))
        pinRow.value(onOff(settings.requirePin))
        autostartRow.value(onOff(settings.startAutomatically))
        lyricsRow.value(onOff(settings.lyricsEnabled))
        ytmRow.value(onOff(settings.lyricsYoutubeMusic)).visible(settings.lyricsEnabled)
        timingRow.value(lyricsTimingValue()).visible(settings.lyricsEnabled)
        sleepRow.value(sleepValue())
        returnRow.value(onOff(settings.returnToPlayer))
        tvMenuRow.value(tvMenuValue())
        visualizerRow.value(onOff(settings.visualizer))
        takeoverRow.value(
            getString(
                when (settings.takeover) {
                    Settings.TAKEOVER_KEEP -> R.string.value_takeover_keep
                    Settings.TAKEOVER_ASK -> R.string.value_takeover_ask
                    else -> R.string.value_takeover_replace
                },
            ),
        )
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
            showOverlaySetup()
        } catch (_: SecurityException) {
            showOverlaySetup()
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
