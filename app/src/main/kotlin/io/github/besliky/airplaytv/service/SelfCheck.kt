package io.github.besliky.airplaytv.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import io.github.besliky.airplaytv.Settings
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Asks the receiver about itself and says in plain words why a phone might not see this TV: whether it runs and listens, whether its
 * name is announced on the network, which network and Wi-Fi band the stick is on, and whether a VPN is in the way (some VPNs hide the
 * devices of the home network from phones). The judging is a plain function of what was found, so it can be tested.
 */
object SelfCheck {

    enum class Level { OK, WARN, FAIL }

    data class Finding(val level: Level, val text: String)

    data class Inputs(
        val enabled: Boolean,
        val status: ReceiverState.Status,
        val port: Int,
        val portOpen: Boolean,
        val publishedName: String,
        val hasNetwork: Boolean,
        val wifi: Boolean,
        val wifiMhz: Int,
        val vpnActive: Boolean,
    )

    fun evaluate(i: Inputs): List<Finding> {
        val out = ArrayList<Finding>()
        if (!i.enabled) {
            out += Finding(Level.FAIL, "AirPlay is switched off in this app's settings.")
            return out
        }
        if (!i.hasNetwork) {
            out += Finding(Level.FAIL, "This stick is not on a network.")
            return out
        }
        if (i.status == ReceiverState.Status.ERROR) {
            out += Finding(Level.FAIL, "The receiver did not start. It tries again by itself.")
        } else if (!i.portOpen) {
            out += Finding(Level.FAIL, "Nothing answers on port ${i.port.takeIf { it > 0 } ?: 7000} of this stick. The receiver restarts itself when it finds this.")
        } else {
            out += Finding(Level.OK, "The receiver is listening on port ${i.port}.")
        }
        if (i.publishedName.isEmpty()) {
            out += Finding(Level.WARN, "The name is not announced on the network yet, so phones cannot see this TV. It is announced again by itself.")
        } else {
            out += Finding(Level.OK, "Phones see this TV as “${i.publishedName}”.")
        }
        if (i.wifi) {
            when {
                i.wifiMhz in 2400..2500 ->
                    out += Finding(Level.WARN, "The stick is on the 2.4 GHz Wi-Fi band. AirPlay is smoother on 5 GHz or over a cable, and phones on 5 GHz may not find it.")
                i.wifiMhz >= 4900 -> out += Finding(Level.OK, "The stick is on the 5 GHz Wi-Fi band.")
            }
        } else {
            out += Finding(Level.OK, "The stick is on a cable.")
        }
        if (i.vpnActive) {
            out += Finding(
                Level.WARN,
                "A VPN is on. Some VPNs hide the devices of your home network from phones: in the VPN app, allow local network access.",
            )
        }
        return out
    }

    /** What the findings read as on a screen: one line each, with a mark. */
    fun text(findings: List<Finding>): String = findings.joinToString("\n\n") {
        val mark = when (it.level) {
            Level.OK -> "✓"
            Level.WARN -> "!"
            Level.FAIL -> "✗"
        }
        "$mark  ${it.text}"
    }

    /** Whether something answers on [port] of this stick itself. Blocking, but quick. */
    fun portOpen(port: Int): Boolean = port > 0 && try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 800) }
        true
    } catch (_: Exception) {
        false
    }

    /** Gathers what [evaluate] needs from the system and the receiver's state. Blocking: not for the main thread. */
    @Suppress("DEPRECATION")
    fun gather(context: Context, settings: Settings): Inputs {
        val state = ReceiverState.current
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var wifi = false
        var vpn = false
        var online = false
        try {
            for (network in connectivity.allNetworks) {
                val caps = connectivity.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifi = true
                    online = true
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    online = true
                }
            }
        } catch (_: RuntimeException) {
            // leave what was found
        }
        val mhz = try {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.frequency ?: 0
        } catch (_: RuntimeException) {
            0
        }
        return Inputs(
            enabled = settings.enabled,
            status = state.status,
            port = state.port,
            portOpen = portOpen(state.port),
            publishedName = state.publishedName,
            hasNetwork = online,
            wifi = wifi,
            wifiMhz = mhz,
            vpnActive = vpn,
        )
    }
}
