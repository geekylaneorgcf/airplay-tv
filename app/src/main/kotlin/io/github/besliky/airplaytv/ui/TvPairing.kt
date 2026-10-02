package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.lg.LgDiscovery
import io.github.besliky.airplaytv.lg.LgSetup
import io.github.besliky.airplaytv.lg.LgTv
import io.github.besliky.airplaytv.lg.TvSettings
import io.github.besliky.airplaytv.lg.TvTrust
import io.github.besliky.airplaytv.service.AccessibilitySetup
import io.github.besliky.airplaytv.service.MenuKeyService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sets up the TV Settings Button: finds the LG TV, asks the owner to trust its certificate (once, and only that
 * one), has the TV show its pairing prompt (accepted with the TV's own remote), and keeps what it gives.
 */
object TvPairing {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lg-pairing").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** Runs the whole set-up in dialogs on [activity]; [onFinished] is called when it is over, whatever came out. */
    fun start(activity: Activity, settings: Settings, onFinished: () -> Unit) {
        val cancelled = AtomicBoolean(false)
        val progress = Dialogs.progress(
            activity,
            activity.getString(R.string.tv_pairing_title),
            activity.getString(R.string.tv_pairing_looking),
        ) {
            cancelled.set(true)
            onFinished()
        }
        worker.execute {
            val host = settings.lgHost.ifEmpty { discover(activity)?.also { settings.lgHost = it } }
            if (cancelled.get()) return@execute
            if (host == null) {
                main.post {
                    progress.dismiss()
                    // nothing answered the search: the owner can type the address, which is all the rest needs
                    Dialogs.editLine(
                        activity,
                        activity.getString(R.string.tv_address_title),
                        activity.getString(R.string.tv_pairing_not_found) + "\n\n" + activity.getString(R.string.tv_address_message),
                        activity.getString(R.string.tv_address_hint),
                        "",
                        LgDiscovery::cleanAddress,
                    ) { typed ->
                        settings.lgHost = typed
                        start(activity, settings, onFinished)
                    }
                    onFinished()
                }
                return@execute
            }
            main.post { if (!cancelled.get()) progress.update(activity.getString(R.string.tv_pairing_connecting, host)) }
            val outcome = LgTv(TvSettings.connector(settings)).press(host, settings.lgClientKey.ifEmpty { null }, null) {
                main.post { if (!cancelled.get()) progress.update(activity.getString(R.string.tv_pairing_accept)) }
            }
            if (cancelled.get()) return@execute
            main.post {
                progress.dismiss()
                finish(activity, settings, host, outcome, onFinished)
            }
        }
    }

    /** Looks for the TV; some devices only deliver multicast answers while an app holds a multicast lock. */
    private fun discover(activity: Activity): String? {
        val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock("airplaytv-lg-search")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: RuntimeException) {
            null
        }
        try {
            return LgDiscovery.find()
        } finally {
            try {
                lock?.release()
            } catch (_: RuntimeException) {
                // already released
            }
        }
    }

    private fun finish(activity: Activity, settings: Settings, host: String, outcome: LgTv.Outcome, onFinished: () -> Unit) {
        when (outcome) {
            is LgTv.Outcome.Done -> {
                settings.lgHost = host
                settings.lgClientKey = outcome.clientKey
                settings.tvMenuButton = true
                // the owner is looking at this stick's picture right now: which input of the TV that is, and its hardware address, are worth keeping
                worker.execute { LgSetup.learn(settings) }
                onFinished()
                Dialogs.message(activity, activity.getString(R.string.tv_pairing_done_title), activity.getString(R.string.tv_pairing_done_message))
            }
            is LgTv.Outcome.Untrusted -> {
                val certificate = outcome.error.certificate
                Dialogs.decide(
                    activity,
                    activity.getString(R.string.tv_trust_title),
                    activity.getString(R.string.tv_trust_message, host, TvTrust.fingerprint(certificate)),
                    activity.getString(R.string.tv_trust_confirm),
                ) {
                    settings.lgCertificate = TvTrust.encode(certificate)
                    start(activity, settings, onFinished)
                }
                onFinished()
            }
            LgTv.Outcome.Declined, LgTv.Outcome.NoAnswer -> problem(activity, activity.getString(R.string.tv_pairing_declined), onFinished)
            is LgTv.Outcome.Unreachable -> {
                // the address may be stale; the next attempt looks for the TV again
                settings.lgHost = ""
                problem(activity, activity.getString(R.string.tv_pairing_unreachable, host), onFinished)
            }
            is LgTv.Outcome.Failed -> problem(activity, activity.getString(R.string.tv_pairing_failed), onFinished)
        }
    }

    private fun problem(activity: Activity, message: String, onFinished: () -> Unit) {
        onFinished()
        Dialogs.message(activity, activity.getString(R.string.tv_pairing_failed_title), message)
    }

    /** The commands that switch the Menu button service on, so the button works in every app. */
    fun showEveryAppSetup(activity: Activity) {
        Dialogs.steps(
            activity,
            activity.getString(R.string.menu_setup_title),
            activity.getString(R.string.menu_setup_intro),
            AccessibilitySetup.commands(activity, MenuKeyService.component(activity.packageName)),
            activity.getString(R.string.menu_setup_outro),
        )
    }
}
