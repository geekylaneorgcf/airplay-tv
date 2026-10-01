package io.github.besliky.airplaytv.lg

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import java.util.concurrent.Executors

/**
 * The Menu button's job: open the quick settings of the LG TV (picture mode, sound output, ...) by pressing
 * the TV's own gear button over the network. Used by the player and by [io.github.besliky.airplaytv.service.MenuKeyService],
 * which is what lets the button work in every app.
 */
object TvSettings {

    private const val TAG = "AirPlayTV-LG"

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lg-tv").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    enum class State { OFF, NEEDS_SETUP, READY }

    fun state(settings: Settings): State = when {
        !settings.tvMenuButton -> State.OFF
        settings.lgClientKey.isEmpty() || settings.lgCertificate.isEmpty() -> State.NEEDS_SETUP
        else -> State.READY
    }

    /** A connector that checks the TV's certificate against the one the owner pinned. */
    fun connector(settings: Settings): LgTv.Connector {
        val trust = TvTrust(TvTrust.decode(settings.lgCertificate))
        return LgTv.Connector { host, port, secure, path -> WebSocketClient.connect(host, port, secure, path, trust) }
    }

    /**
     * Presses the gear button on the TV. Never asks anything: with the set-up unfinished it says so, and
     * every failure ends in a short message on screen.
     */
    fun open(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val settings = Settings(app)
            if (state(settings) != State.READY) {
                toast(app, R.string.tv_menu_needs_setup)
                return@execute
            }
            when (val outcome = pressMenu(settings, app)) {
                is LgTv.Outcome.Done -> {
                    if (outcome.clientKey != settings.lgClientKey) settings.lgClientKey = outcome.clientKey
                }
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
            }
        }
    }

    private fun pressMenu(settings: Settings, app: Context): LgTv.Outcome {
        val tv = LgTv(connector(settings))
        val key = settings.lgClientKey.ifEmpty { null }
        val stored = settings.lgHost
        var outcome: LgTv.Outcome = if (stored.isNotEmpty()) {
            tv.press(stored, key, LgTv.MENU) { toast(app, R.string.tv_menu_accept) }
        } else {
            LgTv.Outcome.Unreachable("no address yet")
        }
        if (outcome is LgTv.Outcome.Unreachable) {
            // the TV may have a new address
            val found = LgDiscovery.find()
            if (found != null && found != stored) {
                settings.lgHost = found
                outcome = tv.press(found, key, LgTv.MENU) { toast(app, R.string.tv_menu_accept) }
            }
        }
        return outcome
    }

    private fun toast(app: Context, text: Int) {
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }
}
