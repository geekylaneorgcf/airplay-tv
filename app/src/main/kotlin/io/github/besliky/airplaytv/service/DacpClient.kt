package io.github.besliky.airplaytv.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Sends transport commands back to the AirPlay sender (DACP, the iTunes remote protocol), so the
 * TV remote can pause and skip what a phone is playing.
 *
 * The sender names its DACP service in the RTSP `DACP-ID` header (advertised over mDNS as
 * `iTunes_Ctrl_<id>`) and gives a token in `Active-Remote` that every command must echo. Commands
 * are plain `GET http://host:port/ctrl-int/1/<command>` requests.
 */
class DacpClient(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var dacpId: String? = null
    private var activeRemote: String? = null
    private var host: String? = null
    private var port = 0
    private var discovery: NsdManager.DiscoveryListener? = null

    /** Called when a sender reports its remote-control identity. Starts discovering its service. */
    fun configure(id: String, token: String) {
        if (id.isBlank() || token.isBlank()) return
        synchronized(lock) {
            if (id == dacpId && token == activeRemote && (host != null || discovery != null)) return
            dacpId = id
            activeRemote = token
            host = null
            port = 0
        }
        Log.i(TAG, "remote identity received, looking for iTunes_Ctrl_$id")
        startDiscovery("iTunes_Ctrl_$id")
    }

    /** Forgets the sender, at the end of a session. */
    fun clear() {
        stopDiscovery()
        synchronized(lock) {
            dacpId = null
            activeRemote = null
            host = null
            port = 0
        }
    }

    fun send(command: String) {
        val token: String
        val h: String
        val p: Int
        synchronized(lock) {
            token = activeRemote ?: return
            h = host ?: run {
                Log.w(TAG, "$command dropped: sender service not resolved yet")
                return
            }
            p = port
        }
        executor.execute {
            // A plain socket, not HttpURLConnection: the platform blocks cleartext HTTP by default
            // (targetSdk 28+), and this protocol is cleartext on the local network by design. Only
            // this one request is affected, so the app-wide policy stays strict.
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(h, p), TIMEOUT_MS)
                    socket.soTimeout = TIMEOUT_MS
                    val request = "GET /ctrl-int/1/$command HTTP/1.1\r\nHost: $h:$p\r\n" +
                        "Active-Remote: $token\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(request.toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    val status = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                    Log.i(TAG, "$command -> $status")
                }
            } catch (e: Exception) {
                Log.w(TAG, "$command failed", e)
            }
        }
    }

    fun shutdown() {
        clear()
        executor.shutdownNow()
    }

    private fun startDiscovery(serviceName: String) {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.equals(serviceName, ignoreCase = true)) resolve(info)
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "discovery failed", e)
            discovery = null
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    synchronized(lock) {
                        host = resolved.host?.hostAddress
                        port = resolved.port
                    }
                    Log.i(TAG, "sender remote service at ${resolved.host?.hostAddress}:${resolved.port}")
                    stopDiscovery()
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed: $errorCode")
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "resolve failed", e)
        }
    }

    private fun stopDiscovery() {
        val listener = discovery ?: return
        discovery = null
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (e: RuntimeException) {
            // already stopped
        }
    }

    companion object {
        private const val TAG = "AirPlayTV-DACP"
        private const val SERVICE_TYPE = "_dacp._tcp"
        private const val TIMEOUT_MS = 2000

        const val PLAY_PAUSE = "playpause"
        const val NEXT = "nextitem"
        const val PREVIOUS = "previtem"
    }
}

/** Lets the UI send remote commands without a reference to the service. */
object RemoteControl {
    @Volatile
    var client: DacpClient? = null

    fun send(command: String) {
        client?.send(command)
    }
}
