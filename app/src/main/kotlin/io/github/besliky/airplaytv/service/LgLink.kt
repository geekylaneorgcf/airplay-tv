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
import io.github.besliky.airplaytv.lg.LgSetup
import io.github.besliky.airplaytv.lg.LgTv
import io.github.besliky.airplaytv.lg.TvSettings
import io.github.besliky.airplaytv.lg.Wol
import java.net.ConnectException
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
 * - **Waking the TV.** A sender that connects while the TV is off (its remote-control port does not answer, or the TV says it is on
 *   standby) makes the receiver press Home through the key service, which makes the stick ask the TV to switch on over HDMI-CEC just as
 *   the owner's Home press does, and wake it over the network too when it can (Wake-on-LAN, needs the TV's setting "Turn on via Wi-Fi");
 *   then it puts the TV on this stick's input and asks the phone to wait with the music until the picture is up, so the first seconds
 *   are not lost.
 * - **The TV's own volume.** The phone's slider and the remote's volume keys move the TV's volume between zero and what it was when
 *   the phone connected (see [TvVolume]), when the TV lets it be moved; a TV that sends its sound to a fixed output does not, and then
 *   the receiver's own volume is the one that moves. The TV goes back to what it was when the session ends.
 * - **Music mode.** While only music plays the TV's screen goes off (the sound goes on) and comes back at the next key.
 * - **Smart pause.** The phone's music pauses when the TV is switched to another input or turned off.
 *
 * Everything runs on one worker thread; the TV is never waited for on the main thread. Without a paired TV nothing here does anything.
 */
class LgLink(private val context: Context, private val settings: Settings) {

    /** Set by the service: called with true when the TV's volume is in charge (the output is then at full scale) and with false when it is not. */
    @Volatile
    var onTvVolumeInCharge: ((Boolean) -> Unit)? = null

    /** Set by the service: called (on the worker) after Home was pressed to wake the TV, so that it can bring the player back to the front. */
    @Volatile
    var onHomePressed: (() -> Unit)? = null

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "lg-link").apply { isDaemon = true } }

    // asking the phone to pause can take seconds (its remote service may not be known yet), and must not hold up waiting for the TV
    private val pauser = Executors.newSingleThreadExecutor { Thread(it, "lg-pause").apply { isDaemon = true } }

    @Volatile
    private var session: LgSession? = null

    // only touched on the worker
    private var currentApp: String? = null
    private var powerState: String? = null
    private var screenOffByUs = false

    @Volatile
    private var pausedByUs = false
    private var awayChecked = false
    private var tvVolumeBroken = false
    private var tvVolumeInCharge = false
    private var tvReference = -1 // the TV's volume when the phone connected (the top of the slider), -1 until it is known
    private var tvLastSet = -1
    private var tvLastSetAt = 0L
    private var tvRestoreTo = -1 // what the TV goes back to when the session ends: its volume at the start, or what its owner set on it since
    private var musicModeBroken = false
    private var waking = false
    private var lastHomeAt = 0L
    private var wakeFailedAt = 0L

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

    fun sessionStarted(holdPhone: Boolean = true) = work("session start") {
        if (!paired) return@work
        sessionOpen = true
        pausedByUs = false
        screenOffByUs = false
        tvVolumeBroken = false
        tvVolumeInCharge = false
        tvReference = -1
        tvLastSet = -1
        tvRestoreTo = -1
        musicModeBroken = false
        when (probe()) {
            Probe.SILENT ->
                if (settings.tvWake) wakeTv(holdPhone) else Log.i(SERVICE, "LG the TV is off and waking it is switched off")
            // a refusal comes from a machine that is up: the TV is on, but not taking remote control, or this is not the TV's address
            Probe.REFUSED -> Log.i(SERVICE, "LG the TV's address answers but not on its remote-control port: there is nothing to wake")
            Probe.OPEN -> {
                val opened = ensureSession()
                // a TV on standby with its network kept up (quick start) answers, but shows nothing
                val state = opened?.let { askPowerState(it) }
                if (LgFacts.isOnStandby(state)) {
                    if (settings.tvWake) wakeTv(holdPhone) else Log.i(SERVICE, "LG the TV is on standby and waking it is switched off")
                }
            }
        }
    }

    fun sessionEnded() = work("session end") {
        sessionOpen = false
        if (screenOffByUs) turnScreenOn()
        restoreTvVolume()
        if (tvVolumeInCharge) {
            tvVolumeInCharge = false
            onTvVolumeInCharge?.invoke(false)
        }
        tvReference = -1
        tvLastSet = -1
        tvRestoreTo = -1
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
        pauser.shutdownNow()
        worker.shutdownNow()
    }

    // ---- the TV

    /** What the TV's remote-control port does: takes the connection, refuses it (a machine that is up and does not listen), or says nothing. */
    private enum class Probe { OPEN, REFUSED, SILENT }

    private fun probe(timeoutMs: Int = 1500): Probe = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(settings.lgHost, LgTv.WSS_PORT), timeoutMs)
            Probe.OPEN
        }
    } catch (e: ConnectException) {
        if (LgFacts.isRefusal(e.message)) Probe.REFUSED else Probe.SILENT
    } catch (_: Exception) {
        Probe.SILENT
    }

    private fun reachable(timeoutMs: Int = 1500): Boolean = probe(timeoutMs) == Probe.OPEN

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
                opened.subscribe("ssap://audio/getVolume") { message -> work("tv volume") { onTvVolumeMessage(message) } }
                Log.i(SERVICE, "LG connected to the TV")
                try {
                    LgSetup.learnQuietly(opened, settings)
                } catch (e: Exception) {
                    Log.w(SERVICE, "LG could not learn about the TV", e)
                }
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

    /** The TV's power state, asked now; null when it does not say. */
    private fun askPowerState(opened: LgSession): String? =
        opened.request("ssap://com.webos.service.tvpower/power/getPowerState")?.let { LgFacts.powerState(it) }?.also { powerState = it }

    /** Whether the TV is on and not on standby: its port answers and it does not say it is off. */
    private fun tvIsUp(): Boolean {
        if (!reachable(1000)) return false
        val opened = ensureSession() ?: return true // it answers but will not talk: there is nothing more to ask it
        return !LgFacts.isOnStandby(askPowerState(opened))
    }

    /**
     * A Home press, which makes the stick ask the TV to switch on over HDMI-CEC (see [MenuKeyService.pressHome]); at most once in 30 s.
     * False when the key service is off.
     */
    private fun pressHome(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastHomeAt != 0L && now - lastHomeAt < HOME_GAP_MS) return true // pressed a moment ago: that one is still being waited for
        if (!MenuKeyService.pressHome()) return false
        lastHomeAt = now
        Log.i(SERVICE, "LG pressed Home: the stick asks the TV to switch on over HDMI-CEC")
        onHomePressed?.invoke()
        return true
    }

    private fun wakeTv(holdPhone: Boolean) {
        if (waking) return
        waking = true
        try {
            val macs = knownMacs()
            val started = SystemClock.elapsedRealtime()
            val homed = pressHome()
            if (macs.isNotEmpty()) Wol.send(macs, broadcastAddresses())
            if (!homed && macs.isEmpty()) {
                Log.w(SERVICE, "LG the TV is off and cannot be woken: the key service is off (no Home press) and the TV's hardware address is not known (no Wake-on-LAN)")
                return
            }
            Log.i(SERVICE, "LG the TV is off: waking it (${if (homed) "Home press" else "no Home press"}, ${macs.size} network address(es))")
            // the music is held only for a TV that has woken before: a wrong address or a TV that does not wake must not stop the music at every session
            val hold = holdPhone && (wakeFailedAt == 0L || started - wakeFailedAt > RETRY_HOLD_AFTER_MS)
            val pausing = if (hold) pauser.submit(Runnable { pausePhone("waiting for the TV", force = true) }) else null
            var attempt = 1
            var released = false
            while (sessionOpen && SystemClock.elapsedRealtime() - started < WAKE_WAIT_MS) {
                if (macs.isNotEmpty() && attempt % 4 == 0) Wol.send(macs, broadcastAddresses())
                attempt++
                Thread.sleep(1000)
                if (tvIsUp()) break
                if (!released && SystemClock.elapsedRealtime() - started > HOLD_MAX_MS) {
                    // a TV that takes this long: the music goes on, the TV is still waited for
                    released = true
                    finishPausing(pausing)
                    resumePhone()
                }
            }
            if (!sessionOpen) return
            if (!tvIsUp()) {
                wakeFailedAt = SystemClock.elapsedRealtime()
                Log.w(SERVICE, "LG the TV did not wake in ${WAKE_WAIT_MS / 1000} s: is HDMI-CEC (SimpLink) on in the TV's settings, and, for the network way, \"Turn on via Wi-Fi\"?")
                if (!released) {
                    finishPausing(pausing)
                    resumePhone()
                }
                return
            }
            wakeFailedAt = 0L
            Log.i(SERVICE, "LG the TV is on after ${(SystemClock.elapsedRealtime() - started) / 1000} s")
            val opened = ensureSession()
            switchToStick(opened)
            // the picture and sound need a moment to settle after the TV's input changes
            Thread.sleep(SETTLE_MS)
            if (!released) {
                finishPausing(pausing)
                resumePhone()
            }
        } finally {
            waking = false
        }
    }

    /** Lets the attempts to pause the phone finish (they were running while the TV woke), so that it is not asked to go on before it was asked to wait. */
    private fun finishPausing(pausing: java.util.concurrent.Future<*>?) {
        if (pausing == null) return
        try {
            pausing.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            pausing.cancel(true)
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
        val off = LgFacts.isOnStandby(powerState)
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
        if (tvReference < 0) {
            // the first time in a session: does this TV let its volume be moved at all, and where is it now?
            val facts = LgFacts.volume(opened.request("ssap://audio/getVolume") ?: return)
            if (facts == null || !facts.adjustable) {
                tvVolumeBroken = true
                Log.i(SERVICE, "LG the TV's volume is fixed (its sound goes to an external output): the receiver's own volume stays")
                return
            }
            if (facts.muted || facts.level <= 0) {
                tvVolumeBroken = true
                Log.i(SERVICE, "LG the TV is muted or at zero: its volume is left alone and the receiver's own volume stays")
                return
            }
            tvReference = facts.level
            tvLastSet = facts.level
            tvRestoreTo = facts.level
            Log.i(SERVICE, "LG the TV's volume is ${facts.level}: the phone's slider moves it between 0 and that")
        }
        val target = TvVolume.target(VolumeLimits.apply(level, ceilingPercent()), tvReference)
        if (tvVolumeInCharge && target == tvLastSet) return
        val answer = if (target == tvLastSet) null else opened.request("ssap://audio/setVolume", JSONObject().put("volume", target))
        if (answer != null && !LgSession.succeeded(answer)) {
            tvVolumeBroken = true
            if (tvVolumeInCharge) {
                tvVolumeInCharge = false
                onTvVolumeInCharge?.invoke(false)
            }
            Log.w(SERVICE, "LG the TV did not take its volume: $answer")
            return
        }
        if (answer != null) {
            tvLastSet = target
            tvLastSetAt = SystemClock.elapsedRealtime()
        }
        if (!tvVolumeInCharge) {
            tvVolumeInCharge = true
            Log.i(SERVICE, "LG the TV's volume follows the slider ($target of ${tvReference})")
            onTvVolumeInCharge?.invoke(true)
        }
    }

    /** The TV says its volume (it does so after every change, ours too): a change that was not ours moves the top of the slider along. */
    private fun onTvVolumeMessage(message: JSONObject) {
        if (!tvVolumeInCharge || tvReference <= 0) return
        val facts = LgFacts.volume(message) ?: return
        if (facts.level == tvLastSet) return
        if (SystemClock.elapsedRealtime() - tvLastSetAt < OWN_CHANGE_ECHO_MS) return
        val moved = TvVolume.referenceAfterTvChange(facts.level, VolumeLimits.apply(latestLevel, ceilingPercent())) ?: return
        Log.i(SERVICE, "LG the volume was changed on the TV (now ${facts.level}): the top of the phone's slider is now $moved")
        tvReference = moved
        tvLastSet = facts.level
        tvRestoreTo = facts.level
    }

    /** The session is over: the TV goes back to the volume it had when the phone connected, or that its owner set on it since. */
    private fun restoreTvVolume() {
        val back = tvRestoreTo
        if (!tvVolumeInCharge || tvVolumeBroken || back <= 0 || tvLastSet == back) return
        val opened = session?.takeIf { !it.closed } ?: return
        val answer = opened.request("ssap://audio/setVolume", JSONObject().put("volume", back))
        Log.i(SERVICE, "LG the TV's volume goes back to $back: ${if (LgSession.succeeded(answer)) "done" else "no answer"}")
    }

    private companion object {
        const val MUSIC_MODE_AFTER_MS = 12_000L
        const val AWAY_AFTER_MS = 2_000L
        const val TV_OFF_CHECK_MS = 3_000L
        const val WAKE_WAIT_MS = 45_000L
        const val HOLD_MAX_MS = 20_000L
        const val RETRY_HOLD_AFTER_MS = 10 * 60_000L
        const val HOME_GAP_MS = 30_000L
        const val OWN_CHANGE_ECHO_MS = 1_500L
        const val SETTLE_MS = 2_500L
        const val PAUSE_TRIES = 8
        const val PAUSE_RETRY_MS = 700L
    }
}
