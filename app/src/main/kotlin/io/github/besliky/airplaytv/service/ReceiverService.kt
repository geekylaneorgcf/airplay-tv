package io.github.besliky.airplaytv.service

import android.app.PendingIntent
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
import android.view.KeyEvent
import android.widget.Toast
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
import io.github.besliky.airplaytv.ui.TakeoverActivity
import io.github.besliky.airplaytv.ui.VideoPlayerActivity
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.zip.CRC32

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
    private var phoneGain = -1f
    private var trackKey = ""
    private val trackHistory = ArrayList<String>()
    private var lyricsKey = ""
    private var photoToken = 0
    private val photoExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "photo-decode").apply { isDaemon = true } }
    private var coverToken = 0
    private var coverCrc = -1L
    private val coverExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "cover-decode").apply { isDaemon = true } }
    private var publishedMetadata: List<Any?>? = null
    private var lyricsToken = 0
    private val lyricsRunnable = Runnable { requestLyrics() }

    /** Tells the home screen app when something starts or stops playing (see [LauncherLink]). */
    private val launcherListener: (ReceiverState.Snapshot) -> Unit = { LauncherLink.onState(this, it) }

    private lateinit var lg: LgLink
    private lateinit var effects: SoundEffects

    /** True while the TV's own volume is moved with the slider: the receiver's output is then at full scale. */
    private var tvVolumeInCharge = false

    /** The first volume the phone reports in a session is capped by the start level. */
    private var firstVolumeOfSession = true
    private var unpublishedChecks = 0
    private var lastHeal = 0L
    private val healthExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "health").apply { isDaemon = true } }

    /**
     * Fire OS handles the remote's volume and mute keys itself and never lets an app see them, but
     * it moves the system music volume, and on HDMI that changes only a number: the audio stays at
     * full scale because the stick expects the TV to do the volume (LG ignores it). So treat that
     * number as the input: each step away from the middle is one step of the receiver's own volume,
     * which is applied for real, and the system number is put back in the middle so the buttons
     * work at both ends. It is restored when the session ends.
     *
     * That only works while the system lets the number move. With HDMI-CEC on, the stick hands the volume to the
     * TV and calls the output a fixed-volume device: it ignores every change, so the number stays wherever it was
     * (often 0). Read as a key press that is "eight steps down" at every look, and the receiver's volume was
     * pinned to silence. So the watch starts by checking that the number can be parked in the middle, and when it
     * cannot, it stays out of it: the TV's own volume is the remote's, the phone's slider is the receiver's.
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
        takeVolumeKeys(false)
        try {
            savedStreamIndex = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, STREAM_CENTER, 0)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != STREAM_CENTER) {
                Log.i(SESSION, "the system volume cannot be moved here (the TV controls it): the remote's volume keys step the receiver's volume")
                savedStreamIndex = -1
                takeVolumeKeys(true)
                return
            }
        } catch (e: RuntimeException) {
            Log.w(SESSION, "cannot read the system volume", e)
            return
        }
        handler.postDelayed(volumeWatch, VOLUME_WATCH_MS)
    }

    private fun stopVolumeWatch() {
        handler.removeCallbacks(volumeWatch)
        takeVolumeKeys(false)
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
                toggleMute()
            } else if (index != STREAM_CENTER) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, STREAM_CENTER, 0)
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != STREAM_CENTER) {
                    // The output turned into one the system will not move (the TV came on over CEC): that number is
                    // not a key press, so leave the volume alone and stop looking until the next session.
                    Log.i(SESSION, "the system volume stopped moving: the TV controls it now, the remote's volume keys step the receiver's volume")
                    handler.removeCallbacks(volumeWatch)
                    savedStreamIndex = -1
                    takeVolumeKeys(true)
                    return
                }
                setVolumeLevel(VolumeScale.stepped(currentVolume(), index - STREAM_CENTER, OUTPUT_STEPS))
            }
        } catch (e: RuntimeException) {
            Log.w(SESSION, "volume watch failed", e)
        }
    }

    /**
     * While the system will not move its volume, the remote's volume keys are not the system's to handle: the accessibility
     * service hands them over (see [VolumeKeys]) and they step the level the phone's slider also sets, so there is one volume.
     */
    private fun takeVolumeKeys(on: Boolean) {
        if (on) {
            VolumeKeys.take { keyCode -> handler.post { onVolumeKey(keyCode) } }
        } else {
            VolumeKeys.take(null)
        }
    }

    private fun onVolumeKey(keyCode: Int) {
        if (ReceiverState.current.status != Status.CONNECTED) return
        if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            toggleMute()
        } else {
            val step = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
            setVolumeLevel(VolumeScale.stepped(currentVolume(), step, OUTPUT_STEPS))
        }
        Log.i(SESSION, "remote volume key: the level is now ${"%.2f".format(ReceiverState.current.volume)}")
    }

    /** Mute, or the volume from before the mute when it is muted already. */
    private fun toggleMute() {
        val current = currentVolume()
        if (current > 0f) {
            levelBeforeMute = current
            setVolumeLevel(0f)
        } else {
            setVolumeLevel(levelBeforeMute.coerceAtLeast(1f / OUTPUT_STEPS))
        }
    }

    /** The volume as a slider position 0..1; one value for the phone's slider and the TV remote. */
    private fun currentVolume(): Float = ReceiverState.current.volume.let { if (it < 0f) 1f else it }

    /** The remote changed the volume: apply it for real and show it. The phone's next change replaces it. */
    private fun setVolumeLevel(level: Float) {
        applyOutput(level)
        ReceiverState.update { it.copy(volume = level, volumeAtMs = SystemClock.elapsedRealtime()) }
    }

    private fun ceilingNow(): Int =
        VolumeLimits.ceilingPercent(settings.volumeLimits(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

    /**
     * Puts the slider position [level] on the speakers: scaled into the ceiling of the volume limits, and either as the receiver's own
     * gain or, while the TV's volume is in charge, as the TV's volume with the receiver's output at full scale.
     */
    private fun applyOutput(level: Float, phoneGain: Float? = null) {
        val ceiling = ceilingNow()
        val gain = when {
            tvVolumeInCharge -> 1f
            // with no ceiling the phone's own gain is used as it is, down to the faint steps under the slider's range
            ceiling >= 100 && phoneGain != null -> phoneGain
            else -> VolumeScale.levelToGain(VolumeLimits.apply(level, ceiling))
        }
        audio.setVolume(gain)
        Log.i(SESSION, "volume: slider ${"%.2f".format(level)}, ceiling $ceiling%, output gain ${"%.3f".format(gain)}${if (tvVolumeInCharge) " (the TV's volume is in charge)" else ""}")
        lg.volume(level)
    }

    /** The limits or who is in charge of the volume changed: the same slider position, put on the speakers again. */
    private fun reapplyVolume() {
        if (ReceiverState.current.status == Status.CONNECTED) applyOutput(currentVolume())
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
        lookUpLyrics(token, s.title, s.artist, s.durationMs, attempt = 0)
    }

    /**
     * One lookup for the track of [token]. A lookup that failed (no network, rate limit, server error) is
     * not the same as a song without lyrics, so it is tried again a few times, with the screen still
     * saying "Looking for lyrics…", before it is reported as unavailable.
     */
    private fun lookUpLyrics(token: Int, title: String, artist: String, durationMs: Long, attempt: Int) {
        LyricsRepository.load(title, artist, durationMs, BuildConfig.VERSION_NAME, settings.lyricsYoutubeMusic) { outcome ->
            handler.post {
                if (token != lyricsToken) return@post // another track was requested meanwhile
                when (outcome) {
                    is LyricsRepository.Outcome.Found ->
                        ReceiverState.update { it.copy(lyricsState = LyricsState.FOUND, lyrics = outcome.lyrics) }
                    LyricsRepository.Outcome.NotFound ->
                        ReceiverState.update { it.copy(lyricsState = LyricsState.NOT_FOUND, lyrics = null) }
                    LyricsRepository.Outcome.Failed ->
                        if (attempt < LYRICS_RETRY_MS.size) {
                            handler.postDelayed({
                                if (token != lyricsToken) return@postDelayed
                                // the track length may have arrived since the first try
                                val length = ReceiverState.current.durationMs.takeIf { it > 0 } ?: durationMs
                                lookUpLyrics(token, title, artist, length, attempt + 1)
                            }, LYRICS_RETRY_MS[attempt])
                        } else {
                            ReceiverState.update { it.copy(lyricsState = LyricsState.UNAVAILABLE, lyrics = null) }
                        }
                }
            }
        }
    }

    private var lastProgressMs = -1L
    private var lastProgressAt = 0L
    private var prevAnchorMs = -1L
    private val syncSamples = ArrayDeque<Long>()
    private val nativeCounters = LongArray(27)
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

    /** The system screensaver starts when the TV has been left alone; while music plays, show the player instead. */
    private val dreamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = ReceiverState.current
            if (!settings.returnToPlayer || s.status != Status.CONNECTED || !s.audioActive || s.videoActive) return
            Log.i(SESSION, "the screensaver started while music plays: showing the player")
            showNowPlaying(fromScreensaver = true)
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
        effects = SoundEffects(settings)
        audio.onTrack = { sessionId -> effects.attach(sessionId) }
        audio.onStopped = { effects.release() }
        lg = LgLink(this, settings).also { link ->
            link.onTvVolumeInCharge = { inCharge ->
                handler.post {
                    tvVolumeInCharge = inCharge
                    reapplyVolume()
                }
            }
        }
        UserKeys.listener = { lg.userKey() }
        VideoPlayback.onScreenEnded = { handler.post { endUrlVideo(bySender = false) } }
        dacp = DacpClient(this).also { RemoteControl.client = it }
        network = NetworkMonitor(this, ::onNetworkChanged)
        network.start()
        settings.registerListener(prefsListener)
        ReceiverState.observe(launcherListener)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        registerReceiver(dreamReceiver, IntentFilter(Intent.ACTION_DREAMING_STARTED))
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
        ReceiverState.remove(launcherListener)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            unregisterReceiver(dreamReceiver)
            screenReceiverRegistered = false
        }
        if (NativeBridge.listener === this) NativeBridge.listener = null
        stopVolumeWatch()
        UserKeys.listener = null
        VideoPlayback.onScreenEnded = null
        lg.shutdown()
        effects.release()
        healthExecutor.shutdownNow()
        photoExecutor.shutdownNow()
        coverExecutor.shutdownNow()
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
            "", "", // the receiver's own identity, an Apple TV: a different model string stops iOS from starting sessions
            settings.airplayVideo,
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
        NativeBridge.nativeSetTakeover(takeoverPolicy())
        acquireMulticastLock()
        publish()
        updateIdleState()
        checkSpeakerTrial()
        handler.removeCallbacks(healthTick)
        handler.postDelayed(healthTick, HEALTH_MS)
    }

    private fun takeoverPolicy(): Int = if (settings.takeover == Settings.TAKEOVER_REPLACE) 0 else 1

    // ---- self-check: a receiver that has gone quiet is brought back

    private val healthTick = object : Runnable {
        override fun run() {
            checkHealth()
            handler.postDelayed(this, HEALTH_MS)
        }
    }

    /**
     * Fire OS closes background apps on a stick with 1.7 GB, and Wi-Fi blinks. Once a minute, while nobody plays, the receiver checks
     * that something answers on its port and that its name is still announced, and mends what is not: it restarts when the port is
     * silent, and announces the name again when that was lost for two checks in a row.
     */
    private fun checkHealth() {
        if (!running || ReceiverState.current.clientName != null || !network.current.available) return
        val checkedPort = port
        healthExecutor.execute {
            val open = SelfCheck.portOpen(checkedPort)
            handler.post {
                if (!running || ReceiverState.current.clientName != null) return@post
                if (!open) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastHeal > HEAL_MIN_GAP_MS) {
                        lastHeal = now
                        Log.w(SERVICE, "nothing answers on the receiver's port: restarting it")
                        restartReceiver()
                    }
                    return@post
                }
                if (advertiser.isPublished) {
                    unpublishedChecks = 0
                } else if (++unpublishedChecks >= 2) {
                    unpublishedChecks = 0
                    Log.w(SERVICE, "the name is no longer announced: announcing it again")
                    publish()
                    advertiser.refresh()
                }
            }
        }
    }

    /**
     * A speaker trial that has not seen music by its deadline is undone, so a receiver an iPhone refuses to use never stays
     * that way: the owner would only see that nothing plays.
     */
    private val speakerTrialExpiry = Runnable { checkSpeakerTrial() }

    private fun checkSpeakerTrial() {
        handler.removeCallbacks(speakerTrialExpiry)
        val until = settings.speakerTrialUntil
        if (!settings.speakerMode || until <= 0L) return
        val left = until - System.currentTimeMillis()
        if (left <= 0L) {
            Log.i(SESSION, "no music started while appearing as a speaker: back to Apple TV")
            settings.speakerTrialUntil = 0L
            settings.speakerMode = false
        } else {
            handler.postDelayed(speakerTrialExpiry, left + 500L)
        }
    }

    private fun stopReceiver() {
        handler.removeCallbacks(healthTick)
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
        val (airplayTxt, raopTxt) = Appearance.records(txt(raop = false), txt(raop = true), settings.speakerMode)
        advertiser.publish(
            Advertiser.Registration(
                airplayName = name,
                raopName = "${id.deviceIdHex}@$name",
                port = port,
                airplayTxt = airplayTxt,
                raopTxt = raopTxt,
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
            Settings.KEY_BASS, Settings.KEY_TREBLE, Settings.KEY_LOUDNESS, Settings.KEY_NIGHT_MODE -> effects.apply()
            Settings.KEY_TAKEOVER -> if (running && NativeBridge.loaded) NativeBridge.nativeSetTakeover(takeoverPolicy())
            Settings.KEY_VOLUME_MAX, Settings.KEY_VOLUME_NIGHT, Settings.KEY_NIGHT_FROM, Settings.KEY_NIGHT_TO, Settings.KEY_TV_VOLUME -> reapplyVolume()
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
    /**
     * Turns the screen on for a sender, and ends a screensaver. A device that was asleep counts as woken
     * by AirPlay (that is what Sleep After Music undoes). The wake lock is taken even when the device is
     * awake, because a running screensaver counts as interactive and would otherwise stay on top of the
     * player; on a screen that is already on it does nothing.
     */
    private fun wakeDisplay() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) ReceiverState.update { it.copy(wokeDevice = true) }
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

    /**
     * Audio-only senders (music apps): show what is playing, like a TV with built-in AirPlay.
     * [fromScreensaver] starts the player already dimmed to its idle stage; [peek] shows it for a few
     * seconds and then goes back to what was on screen.
     */
    private fun showNowPlaying(fromScreensaver: Boolean = false, peek: Boolean = false) {
        if (ReceiverState.current.videoActive) return
        wakeDisplay()
        val intent = Intent(this, NowPlayingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(NowPlayingActivity.EXTRA_IDLE, fromScreensaver)
            .putExtra(NowPlayingActivity.EXTRA_PEEK, peek)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(SESSION, "cannot open the now playing screen", e)
        }
    }

    // ---- native events (main thread) ----

    override fun onSessionStarted(clientName: String, clientModel: String) {
        Log.i(SESSION, "session started ($clientModel)")
        syncSamples.clear()
        ReceiverState.update { it.copy(syncOffsetMs = 0) }
        if (settings.speakerMode && settings.speakerTrialUntil > 0L) {
            Log.i(SESSION, "music started while appearing as a speaker: the trial is over, it stays")
            settings.speakerTrialUntil = 0L
            handler.removeCallbacks(speakerTrialExpiry)
        }
        // Audio-only senders (music apps) never open the playback screen, so wake the display
        // here too; on a TV with HDMI-CEC this switches the TV on so the audio is heard.
        ReceiverState.update { it.copy(wokeDevice = false) }
        wakeDisplay()
        acquireSessionLocks()
        startVolumeWatch()
        lg.sessionStarted()
        firstVolumeOfSession = true
        phoneGain = -1f
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
        lg.sessionEnded()
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
        coverToken++ // a cover still being decoded belongs to the session that ended
        coverCrc = -1L
        phoneGain = -1f
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
        lg.videoStarted()
        showPlayback()
    }

    // ---- AirPlay video: the sender gives the address of a video and this receiver plays it

    override fun onVideoPlay(url: String, startSeconds: Double, startFraction: Double) {
        Log.i(SESSION, "AirPlay video from ${VideoPlayback.hostOf(url)}")
        if (!ReceiverState.current.urlVideo) {
            wakeDisplay()
            acquireSessionLocks()
            lg.sessionStarted(holdPhone = false)
        }
        ReceiverState.update { it.copy(urlVideo = true, status = Status.CONNECTED, clientName = it.clientName ?: "iPhone") }
        val intent = Intent(this, VideoPlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(VideoPlayerActivity.EXTRA_URL, url)
            .putExtra(VideoPlayerActivity.EXTRA_START, startSeconds)
            .putExtra(VideoPlayerActivity.EXTRA_FRACTION, startFraction)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(SESSION, "cannot open the video screen", e)
        }
        enterForeground(statusText())
    }

    override fun onVideoRate(rate: Double) {
        VideoPlayback.controller?.setRate(rate)
    }

    override fun onVideoScrub(seconds: Double) {
        VideoPlayback.controller?.seekTo(seconds)
    }

    override fun onVideoEnd() = endUrlVideo(bySender = true)

    /** The video is over: the sender said so ([bySender]), or its screen was left. */
    private fun endUrlVideo(bySender: Boolean) {
        if (!ReceiverState.current.urlVideo) return
        Log.i(SESSION, "AirPlay video ended${if (bySender) "" else " (the screen was left)"}")
        if (bySender) {
            VideoPlayback.controller?.stop()
        } else if (NativeBridge.loaded) {
            NativeBridge.nativeSetPlayback(0.0, 0.0, 0.0, false)
        }
        ReceiverState.update { it.copy(urlVideo = false, clientName = if (it.audioActive || it.videoActive) it.clientName else null) }
        if (!ReceiverState.current.audioActive && !ReceiverState.current.videoActive) {
            releaseSessionLocks()
            lg.sessionEnded()
        }
        if (restartPending && ReceiverState.current.clientName == null) restartReceiver() else updateIdleState()
    }

    /** A second phone was turned away because the first is kept: say so, or, when the owner wants to be asked, ask. */
    override fun onSessionBlocked(clientName: String) {
        val current = ReceiverState.current.clientName ?: getString(R.string.takeover_somebody)
        if (settings.takeover == Settings.TAKEOVER_ASK) {
            try {
                startActivity(
                    Intent(this, TakeoverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(TakeoverActivity.EXTRA_NAME, clientName),
                )
            } catch (e: RuntimeException) {
                Log.w(SESSION, "cannot ask whether $clientName may play", e)
            }
        } else {
            Toast.makeText(this, getString(R.string.takeover_blocked, clientName, current), Toast.LENGTH_LONG).show()
        }
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
        lg.audioStarted()
        showNowPlaying()
    }

    override fun onAudioStopped() {
        audio.stop()
        ReceiverState.update { it.copy(audioActive = false) }
    }

    override fun onVolume(gain: Float) {
        // The core reports the sender's volume again each time a stream starts, so a new track or a
        // resume repeats the same number. That is not the slider moving: it must not undo a volume
        // set with the TV remote, and it must not count as someone using the screen.
        val repeated = phoneGain >= 0f && VolumeScale.sameGain(gain, phoneGain)
        phoneGain = gain
        if (repeated) return
        var level = VolumeScale.gainToLevel(gain)
        var exactGain: Float? = gain
        if (firstVolumeOfSession) {
            // a song does not start louder than the owner chose, whatever the phone's slider says
            firstVolumeOfSession = false
            val started = VolumeLimits.startLevel(level, settings.volumeLimits())
            if (started != level) {
                level = started
                exactGain = null
            }
        }
        applyOutput(level, exactGain)
        ReceiverState.update { it.copy(volume = level, volumeAtMs = SystemClock.elapsedRealtime()) }
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
        val crc = CRC32().also { it.update(data) }.value
        if (crc == coverCrc && ReceiverState.current.artwork != null) {
            // The same cover again (the next song of an album, or a sender that sends it twice): keep the
            // picture and the colours made from it, and only say that one arrived, so the player does not
            // slide the cover in over itself. A different cover still being decoded is out of date now.
            Log.i(SESSION, "the same cover again (${data.size} bytes)")
            coverToken++
            ReceiverState.update { it.copy(artworkSeq = it.artworkSeq + 1) }
            return
        }
        // Decoding and picking colours take a few dozen milliseconds. Done here they would hold up the main
        // thread, and with it every animation, in the very moment the player slides the new cover in.
        val token = ++coverToken
        coverExecutor.execute {
            val bitmap = decodeArtwork(data) ?: return@execute
            val colors = ArtworkColors.from(bitmap)
            // have the render thread turn it into a texture now, here, so the first frame of the slide does not wait for it
            bitmap.prepareToDraw()
            handler.post {
                if (token != coverToken) return@post // a newer cover came meanwhile, or the session ended
                Log.i(SESSION, "new cover (${data.size} bytes)")
                coverCrc = crc
                ReceiverState.update { it.copy(artwork = bitmap, artworkSeq = it.artworkSeq + 1, artColors = colors) }
                refreshMediaSession()
            }
        }
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
            it.copy(durationMs = durationMs, positionMs = lastProgressMs, positionAtMs = now, progressSeq = it.progressSeq + 1)
        }
        measureSync(start, lastProgressMs, rate)
        refreshMediaSession()
    }

    /**
     * Compares where the sender says the song is with where the sound being played is (from the newest packet's
     * position and what is still queued), so the lyrics follow what is heard. The median of a few measurements is kept.
     */
    private fun measureSync(start: Long, reportedMs: Long, rate: Long) {
        if (!NativeBridge.loaded) return
        NativeBridge.nativeStats(nativeCounters)
        val ringMs = nativeCounters[20]
        val queued = ringMs + audio.bufferMs + OUTPUT_DELAY_MS
        val offset = SyncMath.offsetMs(start, reportedMs, nativeCounters[25], nativeCounters[26], queued, rate) ?: return
        syncSamples.addLast(offset)
        while (syncSamples.size > SYNC_SAMPLES) syncSamples.removeFirst()
        val median = SyncMath.median(syncSamples.toList())
        Log.i(SESSION, "sync: the sound heard is $offset ms after the reported position (ring $ringMs ms, output ${audio.bufferMs} ms); lyrics use $median ms")
        ReceiverState.update { it.copy(syncOffsetMs = median) }
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

            /** A media key on the remote: do what it says, and show the player for a moment so the change can be seen. */
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val key = if (Build.VERSION.SDK_INT >= 33) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (key != null && key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0 && settings.returnToPlayer) {
                    showNowPlaying(peek = true)
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        session.setSessionActivity(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, NowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        session.isActive = true
        mediaSession = session
        publishedMetadata = null // a new session knows nothing yet
        refreshMediaSession()
    }

    private fun refreshMediaSession() {
        val session = mediaSession ?: return
        val s = ReceiverState.current
        // A skip makes half a dozen updates in half a second, most of them only the position or the pause flag.
        // The metadata (with the cover, which the system scales and copies each time) goes out only when it changed.
        val key = listOf(s.title, s.artist, s.album, s.durationMs, s.artwork?.let { System.identityHashCode(it) })
        if (key != publishedMetadata) {
            publishedMetadata = key
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
            if (s.durationMs > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
            s.artwork?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            session.setMetadata(metadata.build())
        }
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
        publishedMetadata = null
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

        /** Latency of the audio system beyond the output buffer, a guess that the measurement of a real song can refine. */
        private const val OUTPUT_DELAY_MS = 60L
        private const val SYNC_SAMPLES = 7

        private const val TRACK_HISTORY = 8
        private const val LYRICS_DELAY_MS = 800L
        private val LYRICS_RETRY_MS = longArrayOf(4_000, 12_000, 30_000)

        private const val HEALTH_MS = 60_000L
        private const val HEAL_MIN_GAP_MS = 2 * 60_000L

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
