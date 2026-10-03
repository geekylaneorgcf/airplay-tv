package io.github.besliky.airplaytv.service

import android.content.Context
import android.os.SystemClock
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.core.NativeBridge
import io.github.besliky.airplaytv.lg.TvControl
import io.github.besliky.airplaytv.lg.TvScenes

/**
 * What the quick panel, the Menu key's hold and the sleep timer do to the TV: apply a scene, turn the screen off, turn the TV off and let
 * the stick sleep. Each one runs on the TV thread ([TvOps]) and says in a notice what it did, and what it could not.
 */
object TvActions {

    /** A TV screen that was turned off from here comes back at the next key (see [onAnyKey]). */
    @Volatile
    private var screenOffByUs = false

    // main thread only
    private var countdown: Runnable? = null
    private var countdownAt = 0L

    /** Applies the scene [id] as the owner set it up: the sound and picture first, the input last (it takes the screen away from the stick). */
    fun applyScene(context: Context, id: String) {
        val app = context.applicationContext
        val settings = Settings(app)
        val scene = settings.tvScene(id)
        TvOps.run("scene $id") {
            val tv = TvControl.open(settings)
            if (tv == null) {
                TvNotices.show("The TV did not answer", amber = true)
                return@run
            }
            try {
                val state = tv.read()
                val failed = ArrayList<String>()
                TvScenes.cappedVolume(state.volume?.level, scene.volume)?.let { if (!tv.setVolume(it)) failed.add("volume") }
                if (scene.sound.isNotEmpty() && !scene.sound.equals(state.sound, ignoreCase = true) && !tv.setSound(scene.sound)) failed.add("sound output")
                if (scene.picture.isNotEmpty() && !scene.picture.equals(state.picture, ignoreCase = true) && !tv.setPicture(scene.picture)) failed.add("picture mode")
                if (scene.screenOff) {
                    if (tv.screenOff()) screenOffByUs = true else failed.add("screen")
                }
                val input = if (scene.input.isEmpty()) null else TvScenes.resolveInput(scene.input, state.inputs)
                if (scene.input.isNotEmpty() && input == null) failed.add("input")
                val name = "Scene: ${TvScenes.label(id)}"
                TvNotices.show(
                    if (failed.isEmpty()) name else "$name (not changed: ${failed.joinToString(", ")})",
                    amber = failed.isNotEmpty(),
                    onTv = input != null,
                )
                if (input != null && input != state.inputId && !tv.switchInput(input)) {
                    TvNotices.show("$name (the input did not change)", amber = true, onTv = true)
                }
                Log.i(SERVICE, "TV scene $id applied${if (failed.isEmpty()) "" else ", not changed: $failed"}")
            } finally {
                tv.close()
            }
        }
    }

    /** Switches the TV to [inputId] (`HDMI_2`); [name] is what the notice calls it. */
    fun switchInput(context: Context, inputId: String, name: String) {
        val settings = Settings(context.applicationContext)
        TvNotices.show("Input: $name", onTv = true)
        TvOps.run("switch input") {
            val tv = TvControl.open(settings)
            if (tv == null) {
                TvNotices.show("The TV did not answer", amber = true)
                return@run
            }
            try {
                if (!tv.switchInput(inputId)) TvNotices.show("The TV did not switch to $name", amber = true, onTv = true)
            } finally {
                tv.close()
            }
        }
    }

    /** The TV's screen goes off (the sound goes on); it comes back at the next key. */
    fun screenOff(context: Context) {
        val settings = Settings(context.applicationContext)
        TvOps.run("screen off") {
            val tv = TvControl.open(settings) ?: return@run
            try {
                if (tv.screenOff()) {
                    screenOffByUs = true
                    TvNotices.show("Screen off · any key brings it back", amber = true)
                } else {
                    TvNotices.show("This TV did not turn its screen off", amber = true)
                }
            } finally {
                tv.close()
            }
        }
    }

    /** Called on the main thread for every key that goes down: a screen turned off from here comes back, and a countdown to turning the TV off is called off. */
    fun onAnyKey() {
        if (screenOffByUs) {
            screenOffByUs = false
            val settings = Settings(appContext ?: return)
            TvOps.run("screen on") {
                val tv = TvControl.open(settings) ?: return@run
                try {
                    tv.screenOn()
                } finally {
                    tv.close()
                }
            }
        }
        val pending = countdown
        if (pending != null && SystemClock.elapsedRealtime() - countdownAt > CANCEL_GRACE_MS) {
            TvOps.cancel(pending)
            countdown = null
            TvNotices.show("Cancelled")
        }
    }

    @Volatile
    var appContext: Context? = null

    /** The TV goes to standby and, a moment later, the stick sleeps; a phone that is playing to the stick is let go first. */
    fun tvOff(context: Context) {
        val app = context.applicationContext
        val settings = Settings(app)
        if (ReceiverState.current.clientName != null) {
            try {
                NativeBridge.nativeDisconnect()
            } catch (e: Throwable) {
                Log.w(SERVICE, "TV off: the phone was not let go", e)
            }
        }
        TvOps.run("tv off") {
            val tv = TvControl.open(settings)
            if (tv == null) {
                TvNotices.show("The TV did not answer", amber = true)
                return@run
            }
            try {
                val ok = tv.turnOff()
                Log.i(SERVICE, "TV off: ${if (ok) "done" else "the TV did not take it"}")
                if (!ok) TvNotices.show("The TV did not turn off", amber = true)
            } finally {
                tv.close()
            }
            TvOps.onMainDelayed(SLEEP_AFTER_MS, Runnable { MenuKeyService.sleepNow() })
        }
    }

    /** The Menu key was held: the TV goes off in [COUNTDOWN_MS] unless a key is pressed first. */
    fun holdTvOff(context: Context) {
        val app = context.applicationContext
        TvOps.onMain {
            countdown?.let { TvOps.cancel(it) }
            val go = Runnable {
                countdown = null
                tvOff(app)
            }
            countdown = go
            countdownAt = SystemClock.elapsedRealtime()
            TvNotices.show("TV off in 3 seconds · any key cancels", amber = true, onTv = true)
            TvOps.onMainDelayed(COUNTDOWN_MS, go)
        }
    }

    private const val COUNTDOWN_MS = 3000L
    private const val CANCEL_GRACE_MS = 400L
    private const val SLEEP_AFTER_MS = 1500L
}
