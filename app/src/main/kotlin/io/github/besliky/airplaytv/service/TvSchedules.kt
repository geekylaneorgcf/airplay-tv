package io.github.besliky.airplaytv.service

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.lg.LgFacts
import io.github.besliky.airplaytv.lg.TvControl
import io.github.besliky.airplaytv.lg.TvSettings
import java.util.Calendar

/** When a key was last pressed on the remote, for the idle turn-off; the key service tells it of every key. */
object TvIdle {
    @Volatile
    var lastKeyAt = SystemClock.elapsedRealtime()

    @Volatile
    var lastActiveAt = SystemClock.elapsedRealtime()

    fun touch() {
        lastKeyAt = SystemClock.elapsedRealtime()
    }
}

/**
 * Two things the stick does for the TV by itself, each off unless chosen (TV Features, Extras):
 *
 * - **Night brightness.** In the hours chosen the TV's OLED brightness is lowered to the level chosen (never raised), and in the morning put
 *   back, if nobody has touched it since. What the night changed is written down (see [Settings.tvOledRestore]), so a restart of the app does
 *   not leave the TV dim.
 * - **Idle turn-off.** When the TV shows this stick and nothing has been pressed or played for the hours chosen, the TV is warned for two
 *   minutes (a key cancels) and turned off. It never does that while the TV shows another input (a game console), and never when the
 *   stick's own input is not known.
 *
 * It looks once a minute and talks to the TV only when something is due.
 */
object TvSchedules {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var warnedAt = 0L
    private var oledUnsupportedLogged = false

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        thread = Thread({ loop(app) }, "tv-schedules").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop(app: Context) {
        val settings = Settings(app)
        try {
            while (running) {
                try {
                    oledTick(app, settings)
                    idleTick(app, settings)
                } catch (e: Exception) {
                    Log.w(SERVICE, "TV schedules failed", e)
                }
                Thread.sleep(TICK_MS)
            }
        } catch (_: InterruptedException) {
            // stopped
        }
    }

    // ---- night brightness

    private fun oledTick(app: Context, settings: Settings) {
        val owed = settings.tvOledRestore
        val wanted = settings.tvOledNight && TvSettings.paired(settings)
        val night = VolumeLimits.inNight(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), settings.tvOledFrom, settings.tvOledTo)
        when {
            wanted && night && owed.isEmpty() -> lower(settings)
            owed.isNotEmpty() && (!wanted || !night) -> restore(settings, owed)
        }
    }

    private fun lower(settings: Settings) {
        val tv = TvControl.open(settings, 1500) ?: return
        try {
            if (LgFacts.isOnStandby(tv.readPower())) return
            val brightness = tv.readBrightness()
            if (brightness == null) {
                if (!oledUnsupportedLogged) {
                    oledUnsupportedLogged = true
                    Log.w(SERVICE, "TV night brightness: this TV reports no OLED brightness setting")
                }
                return
            }
            val target = ScheduleMath.oledTarget(brightness.second, settings.tvOledLevel) ?: return
            if (tv.setBrightness(brightness.first, target)) {
                settings.tvOledRestore = ScheduleMath.oledRecord(brightness.first, brightness.second, target)
                Log.i(SERVICE, "TV night brightness: ${brightness.first} ${brightness.second} to $target")
                TvNotices.show("Night brightness: $target%")
            }
        } finally {
            tv.close()
        }
    }

    private fun restore(settings: Settings, record: String) {
        val owed = ScheduleMath.parseOled(record)
        if (owed == null) {
            settings.tvOledRestore = ""
            return
        }
        val tv = TvControl.open(settings, 1500) ?: return // the TV is off: tried again next minute
        try {
            if (LgFacts.isOnStandby(tv.readPower())) return
            val current = tv.readBrightness()?.takeIf { it.first == owed.first }?.second
            val back = ScheduleMath.oledRestore(owed, current)
            if (back == null || tv.setBrightness(owed.first, back)) {
                settings.tvOledRestore = ""
                if (back != null) TvNotices.show("Brightness back to $back%")
                Log.i(SERVICE, "TV night brightness: ${if (back != null) "back to $back" else "left as the owner set it"}")
            }
        } finally {
            tv.close()
        }
    }

    // ---- idle turn-off

    private fun idleTick(app: Context, settings: Settings) {
        val hours = settings.tvIdleOffHours
        if (hours == 0 || !TvSettings.paired(settings) || settings.lgInput.isEmpty()) {
            warnedAt = 0L
            TvIdle.lastActiveAt = SystemClock.elapsedRealtime()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (soundOrSession(app)) TvIdle.lastActiveAt = now
        val last = maxOf(TvIdle.lastKeyAt, TvIdle.lastActiveAt)
        when (ScheduleMath.idleStep(now, last, hours * HOUR_MS, warnedAt, WARN_MS)) {
            ScheduleMath.Idle.ACTIVE -> if (warnedAt != 0L) {
                warnedAt = 0L
                TvNotices.show("The TV stays on")
            }
            ScheduleMath.Idle.WARN -> if (tvShowsStick(settings)) {
                warnedAt = now
                TvNotices.show("Nothing has played for a while: the TV turns off in 2 minutes · any key cancels", amber = true, onTv = true, forMs = 8_000L)
            } else {
                // the TV is on another input (or off): nothing of the stick's to turn off; looked at again later
                TvIdle.lastActiveAt = now - hours * HOUR_MS + RECHECK_MS
            }
            ScheduleMath.Idle.TURN_OFF -> {
                warnedAt = 0L
                if (tvShowsStick(settings)) {
                    Log.i(SERVICE, "TV idle turn-off after $hours h")
                    TvIdle.lastActiveAt = now
                    TvActions.tvOff(app)
                }
            }
            ScheduleMath.Idle.WAITING -> Unit
        }
    }

    private fun soundOrSession(app: Context): Boolean {
        if (ReceiverState.current.clientName != null) return true
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.isMusicActive
    }

    /** The TV is on and this stick's input is the one in front. */
    private fun tvShowsStick(settings: Settings): Boolean {
        val tv = TvControl.open(settings, 1500) ?: return false
        return try {
            val power = tv.readPower()
            val app = tv.foreground()
            !LgFacts.isOnStandby(power) && LgFacts.showsPicture(power) && app != null && app == settings.lgInput
        } finally {
            tv.close()
        }
    }

    private const val TICK_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val WARN_MS = 2 * 60_000L
    private const val RECHECK_MS = 10 * 60_000L
}
