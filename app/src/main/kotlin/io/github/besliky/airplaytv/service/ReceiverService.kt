package io.github.besliky.airplaytv.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import io.github.besliky.airplaytv.service.ReceiverState.Status
import io.github.besliky.airplaytv.ui.MirrorActivity

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

    private fun showPlayback() {
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

    // ---- native events (main thread) ----

    override fun onSessionStarted(clientName: String, clientModel: String) {
        Log.i(SESSION, "session started ($clientModel)")
        acquireSessionLocks()
        ReceiverState.update {
            it.copy(status = Status.CONNECTED, clientName = clientName, clientModel = clientModel, pin = null)
        }
        enterForeground(statusText())
    }

    override fun onSessionEnded() {
        Log.i(SESSION, "session ended")
        audio.stop()
        releaseSessionLocks()
        Notifications.cancelSessionPrompt(this)
        ReceiverState.update {
            it.copy(clientName = null, clientModel = null, videoActive = false, audioActive = false,
                videoWidth = 0, videoHeight = 0, pin = null)
        }
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
        ReceiverState.update { it.copy(audioActive = true) }
    }

    override fun onAudioStopped() {
        audio.stop()
        ReceiverState.update { it.copy(audioActive = false) }
    }

    override fun onVolume(gain: Float) {
        audio.setVolume(gain)
    }

    companion object {
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
