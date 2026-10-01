package io.github.besliky.airplaytv.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.provider.Settings.canDrawOverlays
import android.view.Display
import io.github.besliky.airplaytv.App
import io.github.besliky.airplaytv.BuildConfig
import io.github.besliky.airplaytv.Identity
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import io.github.besliky.airplaytv.Log.Category.SESSION
import io.github.besliky.airplaytv.PairedDevices
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.core.DecoderSelector
import io.github.besliky.airplaytv.core.NativeBridge
import io.github.besliky.airplaytv.service.ReceiverState.LyricsState
import io.github.besliky.airplaytv.service.ReceiverState.Status
import io.github.besliky.airplaytv.ui.MirrorActivity
import io.github.besliky.airplaytv.ui.NowPlayingActivity
import io.github.besliky.airplaytv.ui.PhotoActivity
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.pow

/**
 * Headless AirPlay receiver. Runs as a foreground service for as long as AirPlay is
 * enabled, keeps the services published while a local network is available, and
 * brings up the playback screen when a sender starts mirroring.
 *
 * Idle cost: one native thread blocked in poll(), the platform mDNS registration and
 * a network callback. Decoders, audio output and wake locks exist only during a
 * session.
 */
class ReceiverService : Service(), NativeBridge.Listener {

    private lateinit var settings: Settings
    private lateinit var paired: PairedDevices
    private lateinit var advertiser: Advertiser
    private lateinit var network: NetworkMonitor
    private lateinit var audio: AudioOutput
    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var port = 0
    private var identity: Identity? = null
    private var restartPending = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioSampleRate = 44100
    private var savedStreamIndex = -1
    private var levelBeforeMute = 1f
    private var trackKey = ""
    private val trackHistory = ArrayList<String>()
    private var lyricsKey = ""
    private var photoToken = 0
    private val photoExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "photo-decode").apply { isDaemon = true } }
    private var lyricsToken = 0
    private val lyricsRunnable = Runnable { requestLyrics() }

    /**
     * Fire OS handles the remote's volume and mute keys itself and never lets an app see them, but
     * it moves the system music volume, and on HDMI that changes only a number: the audio stays at
     * full scale because the stick expects the TV to do the volume (LG ignores it). So treat that
     * number as the input: each step away from the middle is one step of the receiver's own volume,
     * which is applied for real, and the system number is put back in the middle so the buttons
     * work at both ends. It is restored when the session ends.
     */
    private val volumeWatch = object : Runnable {
        override fun run() {
            watchSystemVolume()
            handler.postDelayed(this, VOLUME_WATCH_MS)
        }
    }

    private fun startVolumeWatch() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        handler.removeCallbacks(volumeWatch)
        try {
            savedStreamIndex = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, STREAM_CENTER, 0)
        } catch (e: RuntimeException) {
            Log.w(SESSION, "cannot read the system volume", e)
            return
        }
        handler.postDelayed(volumeWatch, VOLUME_WATCH_MS)
    }

    private fun stopVolumeWatch() {
        handler.removeCallbacks(volumeWatch)
        if (savedStreamIndex >= 0) {
            try {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_MUSIC, savedStreamIndex, 0)
            } catch (e: RuntimeException) {
                Log.w(SESSION, "cannot restore the system volume", e)
            }
            savedStreamIndex = -1
        }
    }

    private fun watchSystemVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
            if (muted) {
                // The mute key: toggle the receiver's own mute and release the system one so the
                // next press is seen again.
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                val current = currentVolume()
                if (current > 0f) {
                    levelBeforeMute = current
                    setVolumeLevel(0f)
                } else {
                    setVolumeLevel(levelBeforeMute.coerceAtLeast(1f / OUTPUT_STEPS))
                }
            } else if (index != STREAM_CENTER) {
                val next = (Math.round(currentVolume() * OUTPUT_STEPS) + (index - STREAM_CENTER))
                    .coerceIn(0, OUTPUT_STEPS) / OUTPUT_STEPS.toFloat()
                am.setStreamVolume(AudioManager.STREAM_MUSIC, STREAM_CENTER, 0)
                setVolumeLevel(next)
            }
        } catch (e: RuntimeException) {
            Log.w(SESSION, "volume watch failed", e)
        }
    }

    /** The volume as a slider position 0..1; one value for the phone's slider and the TV remote. */
    private fun currentVolume(): Float = ReceiverState.current.volume.let { if (it < 0f) 1f else it }

    /** The remote changed the volume: apply it for real and show it. The phone's next change replaces it. */
    private fun setVolumeLevel(level: Float) {
        audio.setVolume(levelToGain(level))
        ReceiverState.update { it.copy(volume = level, volumeAtMs = SystemClock.elapsedRealtime()) }
    }

    // ---- lyrics (only when the user turned them on) ----

    private fun scheduleLyrics(delayMs: Long) {
        if (!settings.lyricsEnabled) return
        handler.removeCallbacks(lyricsRunnable)
        handler.postDelayed(lyricsRunnable, delayMs)
    }

    private fun requestLyrics() {
        if (!settings.lyricsEnabled) return
        val s = ReceiverState.current
        if (s.title.isBlank() || s.artist.isBlank()) {
            ReceiverState.update { it.copy(lyricsState = LyricsState.NOT_FOUND, lyrics = null) }
            return
        }
        val key = "${s.title}\u0000${s.artist}"
        if (key == lyricsKey) return
        lyricsKey = key
        val token = ++lyricsToken
        ReceiverState.update { it.copy(lyricsState = LyricsState.LOADING, lyrics = null) }
        LyricsRepository.load(s.title, s.artist, s.durationMs, BuildConfig.VERSION_NAME) { result ->
            handler.post {
                if (token != lyricsToken) return@post // another track was requested meanwhile
                ReceiverState.update {
                    it.copy(lyricsState = if (result != null) LyricsState.FOUND else LyricsState.NOT_FOUND, lyrics = result)
                }
            }
        }
    }

    private fun gainToLevel(gain: Float): Float {
        // The sender's slider spans -30 dB (quietest) to 0 dB (full); -144 dB means mute.
        val db = if (gain <= 0f) -144f else 20f * log10(gain)
        return ((db + 30f) / 30f).coerceIn(0f, 1f)
    }

    /** The AirPlay slider scale: -30 dB at the bottom, 0 dB at the top, silence at 0. */
    private fun levelToGain(level: Float): Float =
        if (level <= 0f) 0f else 10f.pow((level - 1f) * 30f / 20f)
    private var lastProgressMs = -1L
    private var lastProgressAt = 0L
    private var prevAnchorMs = -1L
    private var prevAnchorAt = 0L
    private var dacp: DacpClient? = null
    private var mediaSession: MediaSession? = null
    private var screenReceiverRegistered = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onSettingChanged(key) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The TV may have slept; make sure the services are announced again.
            if (running) advertiser.refresh()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        paired = PairedDevices(this)
        Notifications.createChannels(this)
        enterForeground(getString(R.string.status_starting))

        applyLogLevel()
        NativeBridge.listener = this
        advertiser = Advertiser(this).apply {
            onPublished = { name -> ReceiverState.update { it.copy(publishedName = name) } }
        }
        audio = AudioOutput(this)
        dacp = DacpClient(this).also { RemoteControl.client = it }
        network = NetworkMonitor(this, ::onNetworkChanged)
        network.start()
        settings.registerListener(prefsListener)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        screenReceiverRegistered = true
        Log.i(SERVICE, "service created (${BuildConfig.VERSION_NAME})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground(statusText())
        when (intent?.action) {
            ACTION_DISCONNECT -> NativeBridge.nativeDisconnect()
            ACTION_RESTART -> when {
                !settings.enabled -> shutdown()
                ReceiverState.current.clientName != null -> restartPending = true
                else -> restartReceiver()
            }
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> if (settings.enabled) startReceiver() else shutdown()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(SERVICE, "service destroyed")
        stopReceiver()
        network.stop()
        settings.unregisterListener(prefsListener)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        if (NativeBridge.listener === this) NativeBridge.listener = null
        stopVolumeWatch()
        photoExecutor.shutdownNow()
        releaseMediaSession()
        RemoteControl.client = null
        dacp?.shutdown()
        dacp = null
        handler.removeCallbacksAndMessages(null)
        ReceiverState.update { it.copy(status = Status.OFF) }
        super.onDestroy()
    }

    private fun enterForeground(text: String) {
        val notification = Notifications.foreground(this, text)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifications.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(Notifications.FOREGROUND_ID, notification)
            }
        } catch (e: RuntimeException) {
            Log.e(SERVICE, "cannot enter the foreground", e)
        }
    }

    private fun shutdown() {
        stopReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun applyLogLevel() {
        Log.verbose = settings.verboseLogging || BuildConfig.DEBUG
        if (NativeBridge.loaded) {
            NativeBridge.nativeSetLogLevel(if (Log.verbose) NativeBridge.LOG_DEBUG else NativeBridge.LOG_WARN)
        }
    }

    // ---- receiver lifecycle ----

    private fun startReceiver() {
        if (running) return
        if (!NativeBridge.loaded) {
            ReceiverState.update { it.copy(status = Status.ERROR, error = "native library missing") }
            return
        }
        ReceiverState.update { it.copy(status = Status.STARTING, deviceName = settings.deviceName, error = null) }
        val id = Identity.load(this)
        identity = id

        val selection = DecoderSelector.select(settings.preferredDecoder.ifEmpty { null })
        NativeBridge.nativeSetDecoderPreferences(selection.avcDecoder, selection.hevcDecoder, selection.options)
        val mode = displayMode(selection)
        Log.i(SERVICE, "advertising ${mode.width}x${mode.height}@${settings.frameRate}" +
            (if (mode.hevc) " HEVC" else "") + ", decoder ${selection.avcDecoder}")

        val seed = id.seedCopy()
        port = NativeBridge.nativeStart(
            settings.deviceName.toByteArray(Charsets.UTF_8), id.deviceId, id.publicId, seed,
            settings.requirePin, DEFAULT_PORT, mode.width, mode.height, settings.frameRate, mode.hevc,
        )
        seed.fill(0)
        if (port <= 0) {
            Log.e(SERVICE, "receiver failed to start")
            ReceiverState.update { it.copy(status = Status.ERROR, error = getString(R.string.status_error)) }
            handler.postDelayed({ if (!running && settings.enabled) startReceiver() }, 5_000)
            return
        }
        running = true
        NativeBridge.nativeSetPairedClients(paired.keys())
        acquireMulticastLock()
        publish()
        updateIdleState()
    }

    private fun stopReceiver() {
        if (!running) return
        running = false
        advertiser.withdraw()
        NativeBridge.nativeStop()
        audio.stop()
        releaseSessionLocks()
        releaseMulticastLock()
        Notifications.cancelSessionPrompt(this)
        ReceiverState.update {
            it.copy(status = Status.OFF, publishedName = "", clientName = null, clientModel = null,
                videoActive = false, audioActive = false, pin = null, port = 0)
        }
    }

    private fun restartReceiver() {
        restartPending = false
        stopReceiver()
        if (settings.enabled) startReceiver()
    }

    private data class Mode(val width: Int, val height: Int, val hevc: Boolean)

    /** Stream size to advertise: the configured limit, capped by the display and decoder. */
    private fun displayMode(selection: DecoderSelector.Selection): Mode {
        var res = settings.resolution
        if (res == Settings.Resolution.UHD && !selection.hevc4k) res = Settings.Resolution.FULL_HD
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val physical = display?.mode?.let { maxOf(it.physicalWidth, it.physicalHeight) } ?: 1920
        if (physical in 1..1280 && res != Settings.Resolution.HD) res = Settings.Resolution.HD
        if (physical in 1281..1920 && res == Settings.Resolution.UHD) res = Settings.Resolution.FULL_HD
        return Mode(res.width, res.height, res == Settings.Resolution.UHD)
    }

    private fun txt(raop: Boolean): Map<String, String> {
        val pairs = NativeBridge.nativeTxtRecords(raop) ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < pairs.size) {
            map[pairs[i]] = pairs[i + 1]
            i += 2
        }
        return map
    }

    private fun publish() {
        val id = identity ?: return
        if (!running || !network.current.available) {
            advertiser.withdraw()
            return
        }
        val name = settings.deviceName
        advertiser.publish(
            Advertiser.Registration(
                airplayName = name,
                raopName = "${id.deviceIdHex}@$name",
                port = port,
                airplayTxt = txt(raop = false),
                raopTxt = txt(raop = true),
            )
        )
    }

    private fun onNetworkChanged(snapshot: NetworkMonitor.Snapshot) {
        ReceiverState.update { it.copy(addresses = snapshot.ipv4, transport = snapshot.transports) }
        if (!running) return
        if (snapshot.available) {
            // A new address must be announced again; a first network only needs the registration.
            val wasPublished = advertiser.isPublished
            publish()
            if (wasPublished) advertiser.refresh()
        } else {
            advertiser.withdraw()
        }
        updateIdleState()
    }

    private fun updateIdleState() {
        if (!running) return
        ReceiverState.update {
            if (it.clientName != null) {
                it.copy(port = port)
            } else {
                it.copy(
                    status = if (network.current.available) Status.READY else Status.NO_NETWORK,
                    deviceName = settings.deviceName,
                    port = port,
                )
            }
        }
        enterForeground(statusText())
    }

    private fun statusText(): String {
        val s = ReceiverState.current
        return when {
            s.clientName != null -> getString(R.string.hint_mirroring, s.clientName)
            !settings.enabled -> getString(R.string.hint_off)
            running && network.current.available -> getString(R.string.notification_ready, settings.deviceName)
            running -> getString(R.string.hint_no_network)
            else -> getString(R.string.status_starting)
        }
    }

    private fun onSettingChanged(key: String?) {
        when (key) {
            Settings.KEY_ENABLED -> if (settings.enabled) startReceiver() else shutdown()
            Settings.KEY_VERBOSE -> applyLogLevel()
            Settings.KEY_LYRICS -> {
                handler.removeCallbacks(lyricsRunnable)
                lyricsToken++
                lyricsKey = ""
                if (settings.lyricsEnabled) {
                    ReceiverState.update { it.copy(lyricsState = LyricsState.LOADING, lyrics = null) }
                    scheduleLyrics(0)
                } else {
                    ReceiverState.update { it.copy(lyricsState = LyricsState.OFF, lyrics = null) }
                }
            }
            in Settings.RESTART_KEYS -> {
                if (ReceiverState.current.clientName != null) {
                    restartPending = true
                } else if (running) {
                    restartReceiver()
                }
            }
        }
    }

    // ---- locks ----

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("airplaytv:mdns").apply {
            setReferenceCounted(false)
            try {
                acquire()
            } catch (e: RuntimeException) {
                Log.w(SERVICE, "multicast lock unavailable", e)
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun acquireSessionLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplaytv:session").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi?.createWifiLock(mode, "airplaytv:session")?.apply {
                setReferenceCounted(false)
                try {
                    acquire()
                } catch (e: RuntimeException) {
                    Log.w(SERVICE, "Wi-Fi lock unavailable", e)
                }
            }
        }
    }

    private fun releaseSessionLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // ---- playback screen ----

    /**
     * Wakes a sleeping display (e.g. a TV that went to standby with the network still up) so the
     * playback screen is visible. A brief screen wake lock is enough; the activity keeps the
     * screen on afterwards. No-op when the display is already on.
     */
    @Suppress("DEPRECATION")
    private fun wakeDisplay() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        ReceiverState.update { it.copy(wokeDevice = true) }
        try {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "airplaytv:wake",
            ).acquire(3_000L)
        } catch (e: RuntimeException) {
            Log.w(SESSION, "cannot wake the display", e)
        }
    }

    private fun showPlayback() {
        wakeDisplay()
        val intent = Intent(this, MirrorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val allowed = Build.VERSION.SDK_INT < 29 || canDrawOverlays(this) || App.isInForeground
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(SESSION, "cannot open the playback screen", e)
        }
        if (!allowed) {
            Log.w(SESSION, "opening the playback screen needs the \"display over other apps\" permission")
            Notifications.showSessionPrompt(this, ReceiverState.current.clientName ?: "iPhone")
        }
    }

    /** Audio-only senders (music apps): show what is playing, like a TV with built-in AirPlay. */
    private fun showNowPlaying() {
        if (ReceiverState.current.videoActive) return
        wakeDisplay()
        val intent = Intent(this, NowPlayingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(SESSION, "cannot open the now playing screen", e)
        }
    }

    // ---- native events (main thread) ----

    override fun onSessionStarted(clientName: String, clientModel: String) {
        Log.i(SESSION, "session started ($clientModel)")
        // Audio-only senders (music apps) never open the playback screen, so wake the display
        // here too; on a TV with HDMI-CEC this switches the TV on so the audio is heard.
        ReceiverState.update { it.copy(wokeDevice = false) }
        wakeDisplay()
        acquireSessionLocks()
        startVolumeWatch()
        trackKey = ""
        trackHistory.clear()
        ReceiverState.update {
            it.copy(
                status = Status.CONNECTED, clientName = clientName, clientModel = clientModel, pin = null,
                lyricsState = if (settings.lyricsEnabled) LyricsState.NOT_FOUND else LyricsState.OFF, lyrics = null,
            )
        }
        enterForeground(statusText())
    }

    override fun onSessionEnded() {
        Log.i(SESSION, "session ended")
        audio.stop()
        stopVolumeWatch()
        releaseSessionLocks()
        Notifications.cancelSessionPrompt(this)
        ReceiverState.update {
            it.copy(clientName = null, clientModel = null, videoActive = false, audioActive = false,
                videoWidth = 0, videoHeight = 0, pin = null, title = "", artist = "", album = "",
                artwork = null, artColors = null, durationMs = -1, positionMs = -1, playing = true,
                lyricsState = LyricsState.OFF, lyrics = null)
        }
        handler.removeCallbacks(lyricsRunnable)
        lyricsToken++
        lyricsKey = ""
        trackKey = ""
        trackHistory.clear()
        dacp?.clear()
        releaseMediaSession()
        lastProgressMs = -1
        lastProgressAt = 0
        prevAnchorMs = -1
        prevAnchorAt = 0
        if (restartPending) restartReceiver() else updateIdleState()
    }

    override fun onPin(pin: String?) {
        ReceiverState.update { it.copy(pin = pin) }
        if (pin != null) showPlayback()
    }

    override fun onClientPaired(key: String, clientName: String) {
        Log.i(Log.Category.PAIRING, "sender paired")
        paired.add(key, clientName)
    }

    override fun onVideoStarted() {
        ReceiverState.update { it.copy(videoActive = true) }
        showPlayback()
    }

    override fun onVideoStopped() {
        ReceiverState.update { it.copy(videoActive = false, videoWidth = 0, videoHeight = 0) }
        Notifications.cancelSessionPrompt(this)
    }

    override fun onVideoSize(width: Int, height: Int) {
        ReceiverState.update { it.copy(videoWidth = width, videoHeight = height) }
    }

    override fun onAudioStarted(sampleRate: Int, channels: Int, lowLatency: Boolean) {
        audio.start(sampleRate, channels, lowLatency)
        if (sampleRate > 0) audioSampleRate = sampleRate
        ReceiverState.update { it.copy(audioActive = true) }
        startMediaSession()
        showNowPlaying()
    }

    override fun onAudioStopped() {
        audio.stop()
        ReceiverState.update { it.copy(audioActive = false) }
    }

    override fun onVolume(gain: Float) {
        audio.setVolume(gain)
        ReceiverState.update { it.copy(volume = gainToLevel(gain), volumeAtMs = SystemClock.elapsedRealtime()) }
    }

    override fun onTrackInfo(title: String, artist: String, album: String) {
        // Do not clear the cover or position here: senders do not push track info, artwork and
        // progress in a fixed order, so clearing would wipe a cover that just arrived. A new
        // track's artwork and progress replace the old ones when they come in.
        val key = "$title\u0000$artist"
        val changed = title.isNotBlank() && key != trackKey
        var direction = 1
        if (changed) {
            // Going back to the track before the current one is a "previous"; anything else is "next".
            direction = if (trackHistory.size >= 2 && trackHistory[trackHistory.size - 2] == key) -1 else 1
            if (direction < 0) trackHistory.removeAt(trackHistory.size - 1) else trackHistory.add(key)
            while (trackHistory.size > TRACK_HISTORY) trackHistory.removeAt(0)
            trackKey = key
        }
        ReceiverState.update {
            it.copy(
                title = title, artist = artist, album = album,
                trackSeq = if (changed) it.trackSeq + 1 else it.trackSeq,
                trackDirection = if (changed) direction else it.trackDirection,
                lyricsState = if (changed) (if (settings.lyricsEnabled) LyricsState.LOADING else LyricsState.OFF) else it.lyricsState,
                lyrics = if (changed) null else it.lyrics,
            )
        }
        if (changed) scheduleLyrics(LYRICS_DELAY_MS)
        refreshMediaSession()
    }

    override fun onArtwork(data: ByteArray) {
        val bitmap = decodeArtwork(data) ?: return
        val colors = ArtworkColors.from(bitmap)
        ReceiverState.update { it.copy(artwork = bitmap, artworkSeq = it.artworkSeq + 1, artColors = colors) }
        refreshMediaSession()
    }

    override fun onProgress(start: Long, current: Long, end: Long) {
        // RTP timestamps wrap at 32 bits; differences are what matter.
        val rate = audioSampleRate.toLong().coerceAtLeast(1)
        val durationMs = ((end - start) and 0xFFFFFFFFL) * 1000 / rate
        val positionMs = ((current - start) and 0xFFFFFFFFL) * 1000 / rate
        if (durationMs <= 0) return
        val now = SystemClock.elapsedRealtime()
        val before = ReceiverState.current
        if (before.positionMs >= 0) {
            prevAnchorMs = if (before.playing) before.positionMs + (now - before.positionAtMs) else before.positionMs
            prevAnchorAt = now
        } else {
            prevAnchorMs = -1
        }
        lastProgressMs = positionMs.coerceAtMost(durationMs)
        lastProgressAt = now
        ReceiverState.update {
            it.copy(durationMs = durationMs, positionMs = lastProgressMs, positionAtMs = now)
        }
        refreshMediaSession()
    }

    override fun onPlaying(playing: Boolean) {
        ReceiverState.update {
            val now = SystemClock.elapsedRealtime()
            var position = it.positionMs
            if (position >= 0 && it.playing && !playing) {
                // The audio ran dry PAUSE_DETECT_MS ago. A sender that pauses sends a progress
                // update just before, and that update repeats a stale position (observed with
                // YouTube Music: off by the whole time since the previous update). So when one
                // arrived in the last moments, discard it and count from the position before it.
                val dryAt = now - PAUSE_DETECT_MS
                val recent = lastProgressAt > 0 && now - lastProgressAt <= PAUSE_PROGRESS_WINDOW_MS
                position = if (recent && prevAnchorMs >= 0) {
                    prevAnchorMs + (dryAt - prevAnchorAt)
                } else {
                    position + (dryAt - it.positionAtMs)
                }
                position = position.coerceIn(0L, it.durationMs.coerceAtLeast(0))
            }
            it.copy(playing = playing, positionMs = position, positionAtMs = now)
        }
        // Playing again after the screen went to sleep (a long pause): bring the screen back.
        if (playing && !(getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
            wakeDisplay()
            showNowPlaying()
        }
        refreshMediaSession()
    }

    override fun onPhoto(key: String, data: ByteArray) {
        // Decoding a big photo takes a moment, so it runs off the main thread. The token makes sure a
        // photo that finishes decoding after a newer one or after the end of the session is dropped.
        val token = ++photoToken
        photoExecutor.execute {
            val bitmap = PhotoDecoder.decode(data)
            handler.post {
                if (token != photoToken) return@post
                if (bitmap == null) {
                    Log.w(SESSION, "cannot decode the photo")
                    return@post
                }
                wakeDisplay()
                ReceiverState.update { it.copy(photo = bitmap, photoSeq = it.photoSeq + 1) }
                val intent = Intent(this, PhotoActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                try {
                    startActivity(intent)
                } catch (e: RuntimeException) {
                    Log.w(SESSION, "cannot open the photo screen", e)
                }
            }
        }
    }

    override fun onPhotoStop() {
        photoToken++
        ReceiverState.update { it.copy(photo = null) }
    }

    override fun onRemote(dacpId: String, activeRemote: String) {
        dacp?.configure(dacpId, activeRemote)
    }

    // ---- media session: routes the remote's media keys to the sender, even when this app is not focused ----

    private fun startMediaSession() {
        if (mediaSession != null) return
        val session = MediaSession(this, "AirPlayTV")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = RemoteControl.send(DacpClient.PLAY_PAUSE)
            override fun onPause() = RemoteControl.send(DacpClient.PLAY_PAUSE)
            override fun onSkipToNext() = RemoteControl.send(DacpClient.NEXT)
            override fun onSkipToPrevious() = RemoteControl.send(DacpClient.PREVIOUS)
            override fun onFastForward() = RemoteControl.send(DacpClient.NEXT)
            override fun onRewind() = RemoteControl.send(DacpClient.PREVIOUS)
        })
        session.isActive = true
        mediaSession = session
        refreshMediaSession()
    }

    private fun refreshMediaSession() {
        val session = mediaSession ?: return
        val s = ReceiverState.current
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
        if (s.durationMs > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
        s.artwork?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(metadata.build())
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        val state = if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val position = if (s.positionMs >= 0) s.positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, if (s.playing) 1f else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
    }

    private fun releaseMediaSession() {
        val session = mediaSession ?: return
        mediaSession = null
        session.isActive = false
        session.release()
    }

    /** Decodes cover art, shrinking it so a large JPEG cannot exhaust memory on a 1.7 GB stick. */
    private fun decodeArtwork(data: ByteArray): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: RuntimeException) {
            Log.w(SESSION, "cannot decode artwork", e)
            null
        }
    }

    companion object {
        /** Must match STARVE_MS in core/android/audio_pipeline.c: how long the audio was dry. */
        private const val PAUSE_DETECT_MS = 300L

        private const val TRACK_HISTORY = 8
        private const val LYRICS_DELAY_MS = 800L

        private const val VOLUME_WATCH_MS = 150L
        private const val STREAM_CENTER = 8
        private const val OUTPUT_STEPS = 16

        /** A progress update this recent before the audio ran dry was sent at the pause and is stale. */
        private const val PAUSE_PROGRESS_WINDOW_MS = 1500L

        const val ACTION_STOP = "io.github.besliky.airplaytv.STOP"
        const val ACTION_DISCONNECT = "io.github.besliky.airplaytv.DISCONNECT"
        const val ACTION_RESTART = "io.github.besliky.airplaytv.RESTART"
        private const val DEFAULT_PORT = 7000

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ReceiverService::class.java))
            } catch (e: RuntimeException) {
                Log.e(SERVICE, "cannot start the receiver service", e)
            }
        }

        /** Restarts the receiver, e.g. after the pairing identity changed. */
        fun restart(context: Context) {
            try {
                context.startForegroundService(Intent(context, ReceiverService::class.java).setAction(ACTION_RESTART))
            } catch (e: RuntimeException) {
                Log.e(SERVICE, "cannot restart the receiver service", e)
            }
        }

        fun disconnect(context: Context) {
            try {
                context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_DISCONNECT))
            } catch (e: RuntimeException) {
                if (NativeBridge.loaded) NativeBridge.nativeDisconnect()
            }
        }
    }
}
