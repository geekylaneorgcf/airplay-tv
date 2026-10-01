package io.github.besliky.airplaytv.lg

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Steering the TV's own menu with the Fire remote. The TV's quick settings open over the network, but the
 * arrow keys, OK and Back of this remote go to the stick, not to the TV, so the menu could be opened and not
 * used. While the menu is open this forwards those keys to the TV as the TV's own remote buttons, and keeps
 * its button socket open so that a held arrow key scrolls smoothly. The Menu button opens the menu and, pressed
 * again, closes it; after a few seconds without a key the keys are the stick's again.
 */
object TvMenuMode {

    private const val TAG = "AirPlayTV-LG"
    private const val IDLE_MS = 10_000L

    /** A TV that has not accepted a connection after this long is off; the owner is told at once and the keys stay the app's. */
    private const val CONNECT_MS = 2_000

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lg-menu").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** True while the TV's menu is open and keys are forwarded; never while the TV is still being reached. */
    @Volatile
    private var active = false

    /** True from the press of Menu until the TV answered or did not; the keys are the app's own meanwhile. */
    @Volatile
    private var connecting = false

    // only touched on the worker
    private var remote: LgTv.Remote? = null

    private val idleEnd = Runnable { end("no key for ${IDLE_MS / 1000} s") }

    val isActive: Boolean get() = active

    /** The TV button a key of the Fire remote stands for while the menu is open, or null for a key that stays the stick's. */
    internal fun buttonFor(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "ENTER"
        KeyEvent.KEYCODE_BACK -> "BACK"
        else -> null
    }

    /**
     * Offers a key event to the mode. Returns true when it was taken (the app in front must not see it). Call it
     * first in an activity's key handling, and from the key service.
     */
    fun onKey(context: Context, event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (!Settings(context).tvMenuButton) return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggle(context)
            return true // the press and its release belong to the TV now
        }
        if (!active) return false
        val button = buttonFor(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            main.removeCallbacks(idleEnd)
            main.postDelayed(idleEnd, IDLE_MS)
            send(button)
        }
        return true
    }

    /** The Menu button: opens the TV's quick settings and starts taking keys, or closes the menu and stops. */
    fun toggle(context: Context) {
        val app = context.applicationContext
        if (active) {
            send("EXIT")
            end("closed with the Menu button")
            return
        }
        if (connecting) return
        val settings = Settings(app)
        if (TvSettings.state(settings) != TvSettings.State.READY) {
            toast(app, R.string.tv_menu_needs_setup)
            return
        }
        connecting = true
        worker.execute {
            try {
                start(app, settings)
            } finally {
                connecting = false
            }
            // a TV that did not answer may have a new address; look for it now so that the next press has it
            if (!active && settings.lgHost.isNotEmpty()) refreshAddress(settings)
        }
    }

    private fun refreshAddress(settings: Settings) {
        val found = LgDiscovery.find()
        if (found != null && found != settings.lgHost) {
            Log.i(TAG, "the TV is at a new address")
            settings.lgHost = found
        }
    }

    private fun send(button: String) {
        worker.execute {
            val open = remote ?: return@execute // still connecting, or it failed: the press is dropped
            try {
                open.press(button)
            } catch (e: IOException) {
                Log.w(TAG, "the TV did not take $button: ${e.message}")
                finish()
            }
        }
    }

    private fun end(why: String) {
        main.removeCallbacks(idleEnd)
        active = false
        worker.execute {
            Log.i(TAG, "TV menu keys back to the stick ($why)")
            finish()
        }
    }

    /** On the worker: closes the sockets and gives the keys back. */
    private fun finish() {
        active = false
        main.removeCallbacks(idleEnd)
        val open = remote ?: return
        remote = null
        try {
            open.close()
        } catch (_: IOException) {
            // gone already
        }
    }

    private fun start(app: Context, settings: Settings) {
        val tv = LgTv(TvSettings.connector(settings, CONNECT_MS))
        val key = settings.lgClientKey.ifEmpty { null }
        val stored = settings.lgHost
        val result = if (stored.isNotEmpty()) {
            tv.openRemote(stored, key) { toast(app, R.string.tv_menu_accept) }
        } else {
            LgTv.RemoteResult.Stopped(LgTv.Outcome.Unreachable("no address yet"))
        }
        when (result) {
            is LgTv.RemoteResult.Ready -> {
                if (result.remote.clientKey != settings.lgClientKey) settings.lgClientKey = result.remote.clientKey
                try {
                    result.remote.press(LgTv.MENU)
                } catch (e: IOException) {
                    Log.w(TAG, "the TV did not take the Menu button: ${e.message}")
                    result.remote.close()
                    toast(app, R.string.tv_menu_failed)
                    return
                }
                remote = result.remote
                active = true // from here the arrow keys, OK and Back steer the TV
                main.removeCallbacks(idleEnd)
                main.postDelayed(idleEnd, IDLE_MS)
                toast(app, R.string.tv_menu_keys)
            }
            is LgTv.RemoteResult.Stopped -> when (val outcome = result.outcome) {
                is LgTv.Outcome.Untrusted -> toast(app, R.string.tv_menu_certificate_changed)
                LgTv.Outcome.Declined, LgTv.Outcome.NoAnswer -> toast(app, R.string.tv_menu_declined)
                is LgTv.Outcome.Unreachable -> {
                    Log.w(TAG, "TV not reachable: ${outcome.reason}")
                    toast(app, R.string.tv_menu_unreachable)
                }
                is LgTv.Outcome.Failed -> {
                    Log.w(TAG, "TV failed: ${outcome.reason}")
                    toast(app, R.string.tv_menu_failed)
                }
                is LgTv.Outcome.Done -> Unit
            }
        }
    }

    private fun toast(app: Context, text: Int) {
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }
}
