package io.github.besliky.airplaytv.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.lg.ArpTable
import io.github.besliky.airplaytv.lg.LgFacts
import io.github.besliky.airplaytv.lg.LgSession
import io.github.besliky.airplaytv.lg.LgTv
import io.github.besliky.airplaytv.lg.TvSettings
import io.github.besliky.airplaytv.lg.Wol
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * What this receiver does with an LG TV that has been paired (see TvPairing), in step with a session:
 *
 * - **Waking the TV.** A sender that connects while the TV is off (its remote-control port does not answer) makes the receiver
 *   wake the TV over the network (Wake-on-LAN, needs the TV's setting "Turn on via Wi-Fi") and put it on this stick's input, and
 *   asks the phone to wait with the music until the picture is up, so the first seconds are not lost.
 * - **The TV's own volume.** The phone's slider and the remote's volume keys move the TV's volume, when the TV lets it be moved;
 *   a TV that sends its sound to a fixed output does not, and then the receiver's own volume is the one that moves.
 * - **Music mode.** While only music plays the TV's screen goes off (the sound goes on) and comes back at the next key.
 * - **Smart pause.** The phone's music pauses when the TV is switched to another input or turned off.
 *
 * Everything runs on one worker thread; the TV is never waited for on the main thread. Without a paired TV nothing here does anything.
 */
class LgLink(private val context: Context, private val settings: Settings) {

    /** Set by the service: called with true when the TV's volume is in charge (the output is then at full scale) and with false when it is not. */
    @Volatile
    var onTvVolumeInCharge: ((Boolean) -> Unit)? = null

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "lg-link").apply { isDaemon = true } }

    @Volatile
    private var session: LgSession? = null

    // only touched on the worker
    private var currentApp: String? = null
    private var powerState: String? = null
    private var screenOffByUs = false
    private var pausedByUs = false
    private var awayChecked = false
    private var tvVolumeBroken = false
    private var tvVolumeInCharge = false
    private var musicModeBroken = false
    private var waking = false

    @Volatile
    private var sessionOpen = false

    private val volumeQueued = AtomicBoolean(false)

    @Volatile
    private var latestLevel = -1f

    private val paired: Boolean get() = TvSettings.paired(settings)

    private fun work(label: String, block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(SERVICE, "LG $label failed", e)
            }
        }
    }

    // ---- events from the service

    fun sessionStarted() = work("session start") {
        if (!paired) return@work
        sessionOpen = true
        pausedByUs = false
        screenOffByUs = false
        tvVolumeBroken = false
        tvVolumeInCharge = false
        musicModeBroken = false
        if (!reachable()) {
            if (settings.tvWake) wakeTv() else Log.i(SERVICE, "LG the TV is off and waking it over the network is switched off")
        } else {
            ensureSession()
        }
    }

    fun sessionEnded() = work("session end") {
        sessionOpen = false
        if (screenOffByUs) turnScreenOn()
        if (tvVolumeInCharge) {
            tvVolumeInCharge = false
            onTvVolumeInCharge?.invoke(false)
        }
        awayChecked = false
        pausedByUs = false
        session?.close()
        session = null
    }

    /** Music (and no picture) has started: after a moment the TV's screen goes off, when that is wanted. */
    fun audioStarted() {
        if (!paired || !settings.musicMode) return
        worker.schedule(Runnable {
            try {
                if (!sessionOpen || musicModeBroken || screenOffByUs) return@Runnable
                val s = ReceiverState.current
                if (!s.audioActive || s.videoActive) return@Runnable
                if (isAway()) return@Runnable
                turnScreenOff()
            } catch (e: Exception) {
                Log.w(SERVICE, "LG music mode failed", e)
            }
        }, MUSIC_MODE_AFTER_MS, TimeUnit.MILLISECONDS)
    }

    /** A picture is being shown (mirroring): the screen must be on. */
    fun videoStarted() = work("video start") { if (screenOffByUs) turnScreenOn() }

    /** Someone pressed a key on the remote: a screen that was turned off for the music comes back. */
    fun userKey() {
        if (screenOffByUs) work("wake the screen") { if (screenOffByUs) turnScreenOn() }
    }

    /** The slider (the phone's or the remote's) is at [level] (0 to 1): the TV's volume follows, when it can. */
    fun volume(level: Float) {
        if (!paired || !settings.tvVolume) return
        latestLevel = level
        if (volumeQueued.compareAndSet(false, true)) {
            work("volume") {
                volumeQueued.set(false)
                applyVolume(latestLevel)
            }
        }
    }

    fun shutdown() {
        sessionOpen = false
        session?.close()
        session = null
        worker.shutdownNow()
    }

    // ---- the TV

    private fun reachable(timeoutMs: Int = 1500): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(settings.lgHost, LgTv.WSS_PORT), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun ensureSession(): LgSession? {
        session?.takeIf { !it.closed }?.let { return it }
        val key = settings.lgClientKey.ifEmpty { null }
        return when (val result = LgTv(TvSettings.connector(settings, 2500)).openSession(settings.lgHost, key)) {
            is LgTv.SessionResult.Ready -> {
                val opened = result.session
                session = opened
                opened.onClosed = { onSessionLost(opened) }
                opened.subscribe("ssap://com.webos.applicationManager/getForegroundAppInfo") { message ->
                    work("foreground app") {
                        currentApp = LgFacts.foregroundApp(message)
                        evaluateAway()
                    }
                }
                opened.subscribe("ssap://com.webos.service.tvpower/power/getPowerState") { message ->
                    work("power state") {
                        powerState = LgFacts.powerState(message)
                        evaluateAway()
                    }
                }
                Log.i(SERVICE, "LG connected to the TV")
                opened
            }
            is LgTv.SessionResult.Stopped -> {
                Log.w(SERVICE, "LG cannot talk to the TV: ${result.outcome}")
                null
            }
        }
    }

    private fun onSessionLost(lost: LgSession) {
        if (session === lost) session = null
        if (!sessionOpen || !settings.smartPause) return
        // a TV that went off drops the connection; a network that blinked does not mean the TV is off
        worker.schedule(Runnable {
            try {
                if (sessionOpen && !reachable()) {
                    Log.i(SERVICE, "LG the TV went off")
                    pausePhone("the TV went off")
                }
            } catch (e: Exception) {
                Log.w(SERVICE, "LG smart pause failed", e)
            }
        }, TV_OFF_CHECK_MS, TimeUnit.MILLISECONDS)
    }

    // ---- waking the TV

    private fun wakeTv() {
        if (waking) return
        waking = true
        try {
            val macs = knownMacs()
            if (macs.isEmpty()) {
                Log.w(SERVICE, "LG the TV is off but its hardware address is not known: pair the TV again while it is on")
                return
            }
            Log.i(SERVICE, "LG the TV is off: waking it (${macs.size} address(es))")
            pausePhone("waiting for the TV", force = true)
            val started = SystemClock.elapsedRealtime()
            var attempt = 0
            while (sessionOpen && SystemClock.elapsedRealtime() - started < WAKE_WAIT_MS) {
                if (attempt % 4 == 0) Wol.send(macs, broadcastAddresses())
                attempt++
                Thread.sleep(1000)
                if (reachable(1000)) break
            }
            if (!sessionOpen) return
            if (!reachable()) {
                Log.w(SERVICE, "LG the TV did not wake in ${WAKE_WAIT_MS / 1000} s: is \"Turn on via Wi-Fi\" on in the TV's settings?")
                resumePhone()
                return
            }
            Log.i(SERVICE, "LG the TV is on after ${(SystemClock.elapsedRealtime() - started) / 1000} s")
            val opened = ensureSession()
            switchToStick(opened)
            // the picture and sound need a moment to settle after the TV's input changes
            Thread.sleep(SETTLE_MS)
            resumePhone()
        } finally {
            waking = false
        }
    }

    private fun knownMacs(): List<ByteArray> {
        val fromSettings = settings.lgMacs.split(',').mapNotNull { Wol.parseMac(it) }
        if (fromSettings.isNotEmpty()) return fromSettings
        // the neighbour table remembers the TV for a while after it was last seen
        return ArpTable.macFor(settings.lgHost, ArpTable.read())?.let { Wol.parseMac(it) }?.let { listOf(it) } ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private fun broadcastAddresses(): List<ByteArray> = try {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.allNetworks.mapNotNull { connectivity.getLinkProperties(it) }
            .flatMap { it.linkAddresses }
            .mapNotNull { Wol.broadcastOf(it.address.address, it.prefixLength) }
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun switchToStick(opened: LgSession?) {
        val input = LgFacts.inputIdOf(settings.lgInput) ?: return
        val answer = opened?.request("ssap://tv/switchInput", JSONObject().put("inputId", input))
        Log.i(SERVICE, "LG switch to $input: ${if (LgSession.succeeded(answer)) "done" else "no"}")
    }

    // ---- keeping the phone's music for the TV

    /**
     * Pauses the phone's music if it plays. The phone's remote service may not be known yet at the start of a session, so it is
     * tried for a few seconds. Never toggles a music that is paused already.
     */
    private fun pausePhone(why: String, force: Boolean = false) {
        if (pausedByUs) return
        for (attempt in 0 until PAUSE_TRIES) {
            val state = ReceiverState.current
            if (!force && !state.playing) return
            if (!sessionOpen) return
            val done = CountDownLatch(1)
            var status = -1
            RemoteControl.send(DacpClient.PLAY_PAUSE) {
                status = it
                done.countDown()
            }
            done.await(2, TimeUnit.SECONDS)
            if (status in 200..299) {
                pausedByUs = true
                Log.i(SERVICE, "LG paused the phone's music ($why)")
                return
            }
            Thread.sleep(PAUSE_RETRY_MS)
        }
        Log.i(SERVICE, "LG could not pause the phone's music ($why)")
    }

    private fun resumePhone() {
        if (!pausedByUs) return
        pausedByUs = false
        if (ReceiverState.current.playing) return // the phone went on by itself
        RemoteControl.send(DacpClient.PLAY_PAUSE)
        Log.i(SERVICE, "LG asked the phone to go on")
    }

    // ---- smart pause

    private fun isAway(): Boolean {
        val learned = settings.lgInput
        val offInput = learned.isNotEmpty() && currentApp != null && currentApp != learned
        val off = powerState != null && (powerState.equals("Suspend", true) || powerState.equals("Active Standby", true))
        return offInput || off
    }

    private fun evaluateAway() {
        if (!settings.smartPause || !sessionOpen || waking) return
        val state = ReceiverState.current
        if (!state.audioActive || state.videoActive || !state.playing) return
        if (!isAway() || awayChecked) return
        awayChecked = true
        // a moment, so that the TV passing through another screen on its way to this input is not taken for leaving
        worker.schedule(Runnable {
            try {
                awayChecked = false
                if (isAway() && ReceiverState.current.playing) pausePhone("the TV is on another input", force = false)
            } catch (e: Exception) {
                Log.w(SERVICE, "LG smart pause failed", e)
            }
        }, AWAY_AFTER_MS, TimeUnit.MILLISECONDS)
    }

    // ---- music mode

    private fun turnScreenOff() {
        val opened = ensureSession() ?: return
        val answer = opened.request("ssap://com.webos.service.tvpower/power/turnOffScreen")
        if (LgSession.succeeded(answer)) {
            screenOffByUs = true
            Log.i(SERVICE, "LG the TV's screen is off for the music")
        } else {
            musicModeBroken = true
            Log.w(SERVICE, "LG this TV does not take \"screen off\": $answer")
        }
    }

    private fun turnScreenOn() {
        screenOffByUs = false
        val opened = ensureSession() ?: return
        val answer = opened.request("ssap://com.webos.service.tvpower/power/turnOnScreen")
        Log.i(SERVICE, "LG the TV's screen is on again: ${if (LgSession.succeeded(answer)) "done" else "no answer"}")
    }

    // ---- the TV's volume

    private fun ceilingPercent(): Int =
        VolumeLimits.ceilingPercent(settings.volumeLimits(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

    private fun applyVolume(level: Float) {
        if (tvVolumeBroken || level < 0f || !sessionOpen) return
        val opened = session?.takeIf { !it.closed } ?: if (reachable()) ensureSession() else null
        if (opened == null) return
        if (!tvVolumeInCharge) {
            // the first time: does this TV let its volume be moved at all?
            val facts = LgFacts.volume(opened.request("ssap://audio/getVolume") ?: return)
            if (facts == null || !facts.adjustable) {
                tvVolumeBroken = true
                Log.i(SERVICE, "LG the TV's volume is fixed (its sound goes to an external output): the receiver's own volume stays")
                return
            }
        }
        val percent = VolumeLimits.tvVolume(level, ceilingPercent())
        val answer = opened.request("ssap://audio/setVolume", JSONObject().put("volume", percent))
        if (!LgSession.succeeded(answer)) {
            tvVolumeBroken = true
            if (tvVolumeInCharge) {
                tvVolumeInCharge = false
                onTvVolumeInCharge?.invoke(false)
            }
            Log.w(SERVICE, "LG the TV did not take its volume: $answer")
            return
        }
        if (!tvVolumeInCharge) {
            tvVolumeInCharge = true
            Log.i(SERVICE, "LG the TV's volume follows the slider ($percent)")
            onTvVolumeInCharge?.invoke(true)
        }
    }

    private companion object {
        const val MUSIC_MODE_AFTER_MS = 12_000L
        const val AWAY_AFTER_MS = 2_000L
        const val TV_OFF_CHECK_MS = 3_000L
        const val WAKE_WAIT_MS = 45_000L
        const val SETTLE_MS = 2_500L
        const val PAUSE_TRIES = 8
        const val PAUSE_RETRY_MS = 700L
    }
}
