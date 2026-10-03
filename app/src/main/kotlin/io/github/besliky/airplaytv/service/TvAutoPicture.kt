package io.github.besliky.airplaytv.service

import android.content.Context
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.lg.LgFacts
import io.github.besliky.airplaytv.lg.TvControl
import io.github.besliky.airplaytv.lg.TvPicture
import io.github.besliky.airplaytv.lg.TvScenes
import io.github.besliky.airplaytv.lg.TvSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sets the TV's picture mode when it goes to a chosen input (by default the PlayStation's): the TV keeps one picture mode per input, so
 * this only has to make sure that the chosen input is in the chosen mode when the owner arrives at it; nothing has to be put back on the
 * way out. It keeps a connection to the TV open only while the setting is on and the TV is reachable, and looks again every half minute
 * when it is not. Off unless switched on: it changes the TV's picture settings.
 */
object TvAutoPicture {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    // what the TV tells arrives on the connection's reading thread, which must stay free to receive the answers to what is asked here
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tv-auto-picture").apply { isDaemon = true } }

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        thread = Thread({ loop(app) }, "tv-auto-picture-loop").apply {
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
                if (!settings.tvAutoPicture || !TvSettings.paired(settings)) {
                    Thread.sleep(IDLE_MS)
                    continue
                }
                val tv = TvControl.open(settings)
                if (tv == null) {
                    Thread.sleep(RETRY_MS)
                    continue
                }
                try {
                    watch(tv, settings)
                } finally {
                    tv.close()
                }
                Thread.sleep(RETRY_MS)
            }
        } catch (_: InterruptedException) {
            // stopped
        }
    }

    private fun watch(tv: TvControl, settings: Settings) {
        val closed = CountDownLatch(1)
        tv.onClosed = { closed.countDown() }
        val inputs = tv.read().inputs
        var inside = false
        tv.onForegroundApp { app ->
            worker.execute {
                try {
                    val target = TvScenes.resolveInput(settings.tvAutoPictureInput, inputs)
                    val here = target != null && LgFacts.inputIdOf(app) == target
                    if (here && !inside) setMode(tv, settings)
                    inside = here
                } catch (e: Exception) {
                    Log.w(SERVICE, "TV auto picture failed", e)
                }
            }
        }
        while (!closed.await(CHECK_MS, TimeUnit.MILLISECONDS)) {
            if (!running || !settings.tvAutoPicture) return
        }
    }

    private fun setMode(tv: TvControl, settings: Settings) {
        val wanted = settings.tvAutoPictureMode
        val now = tv.readPicture() ?: return
        if (now.equals(wanted, ignoreCase = true)) return
        val ok = tv.setPicture(wanted)
        Log.i(SERVICE, "TV auto picture: $wanted on the chosen input: ${if (ok) "done" else "the TV did not take it"}")
        TvNotices.show(if (ok) "Picture: ${TvPicture.label(wanted)}" else "The TV did not take ${TvPicture.label(wanted)}", amber = !ok, onTv = true)
    }

    private const val IDLE_MS = 20_000L
    private const val RETRY_MS = 30_000L
    private const val CHECK_MS = 30_000L
}
