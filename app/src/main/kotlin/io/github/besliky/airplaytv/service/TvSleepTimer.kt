package io.github.besliky.airplaytv.service

import android.content.Context
import io.github.besliky.airplaytv.Settings

/**
 * The sleep timer: after the minutes chosen the TV goes to standby and the stick sleeps. It warns five minutes and one minute before the
 * end (on the stick's screen and on the TV's own, so that a game or a film shows it too); a key pressed in the last minute buys 15 more.
 * It is kept in the settings, so a restart of the app does not lose it. All of it runs on the main thread.
 */
object TvSleepTimer {

    private val pending = ArrayList<Runnable>()

    /** When the timer ends (wall clock, ms), 0 when none runs. */
    fun endsAt(context: Context): Long = Settings(context.applicationContext).tvSleepEndsAt

    /** Starts the timer for [minutes], or stops it for 0. */
    fun start(context: Context, minutes: Int) {
        val app = context.applicationContext
        clear()
        val settings = Settings(app)
        if (minutes <= 0) {
            if (settings.tvSleepEndsAt != 0L) TvNotices.show("Sleep timer off")
            settings.tvSleepEndsAt = 0L
            LauncherLink.sleepTimer(app, 0L)
            return
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
        settings.tvSleepEndsAt = end
        schedule(app, end)
        LauncherLink.sleepTimer(app, end)
        TvNotices.show("Sleep timer: the TV turns off in $minutes min")
    }

    /** Adds 15 minutes to a timer that runs (the home screen's timer cell asks for it); nothing when none runs. */
    fun extend(context: Context) {
        val app = context.applicationContext
        val settings = Settings(app)
        val end = settings.tvSleepEndsAt
        if (end <= System.currentTimeMillis()) return
        clear()
        val extended = end + SleepTimerMath.EXTEND_MS
        settings.tvSleepEndsAt = extended
        schedule(app, extended)
        LauncherLink.sleepTimer(app, extended)
        TvNotices.show("Sleep timer: 15 more minutes")
    }

    /** After a restart of the app: carries on with a timer that was running, or drops one that ended long ago (it must not turn the TV off hours late). */
    fun resume(context: Context) {
        val app = context.applicationContext
        val settings = Settings(app)
        val end = settings.tvSleepEndsAt
        if (end == 0L) return
        val now = System.currentTimeMillis()
        if (end < now - STALE_MS) {
            settings.tvSleepEndsAt = 0L
            return
        }
        clear()
        schedule(app, end)
        LauncherLink.sleepTimer(app, end)
    }

    /** A key went down: in the last minute that adds time. */
    fun onKey(context: Context) {
        val app = context.applicationContext
        val settings = Settings(app)
        val end = settings.tvSleepEndsAt
        val now = System.currentTimeMillis()
        if (!SleepTimerMath.inLastMinute(now, end)) return
        clear()
        val extended = end + SleepTimerMath.EXTEND_MS
        settings.tvSleepEndsAt = extended
        schedule(app, extended)
        LauncherLink.sleepTimer(app, extended)
        TvNotices.show("Sleep timer: 15 more minutes")
    }

    private fun clear() {
        for (step in pending) TvOps.cancel(step)
        pending.clear()
    }

    private fun schedule(app: Context, end: Long) {
        val now = System.currentTimeMillis()
        for (step in SleepTimerMath.steps(now, end)) {
            val run = Runnable {
                when (step.stage) {
                    SleepTimerMath.Stage.FIVE_MINUTES -> TvNotices.show("The TV turns off in 5 minutes", amber = true, onTv = true)
                    SleepTimerMath.Stage.ONE_MINUTE -> TvNotices.show("The TV turns off in 1 minute · any key adds 15 minutes", amber = true, onTv = true)
                    SleepTimerMath.Stage.END -> {
                        Settings(app).tvSleepEndsAt = 0L
                        pending.clear()
                        LauncherLink.sleepTimer(app, 0L)
                        TvActions.tvOff(app)
                    }
                }
            }
            pending.add(run)
            TvOps.onMainDelayed((step.atMs - now).coerceAtLeast(0L), run)
        }
        if (SleepTimerMath.steps(now, end).isEmpty()) {
            // already over (resumed a moment after its end)
            Settings(app).tvSleepEndsAt = 0L
            TvActions.tvOff(app)
        }
    }

    private const val STALE_MS = 60_000L
}
