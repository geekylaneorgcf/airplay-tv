package io.github.besliky.airplaytv.lg

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Finds an LG webOS TV on the local network with a UPnP search (SSDP) for LG's second-screen service. A TV
 * answers even when its screen is off, as long as its network standby is on, which it is for AirPlay.
 */
object LgDiscovery {

    private const val GROUP = "239.255.255.250"
    private const val PORT = 1900
    private const val SERVICE = "urn:lge-com:service:webos-second-screen:1"

    private val SEARCH = (
        "M-SEARCH * HTTP/1.1\r\nHOST: $GROUP:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $SERVICE\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

    /** The address of the first LG TV that answers within [timeoutMs], or null. Blocking. */
    fun find(timeoutMs: Int = 3000): String? {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val group = InetAddress.getByName(GROUP)
                repeat(2) { socket.send(DatagramPacket(SEARCH, SEARCH.size, group, PORT)) }
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                val buffer = ByteArray(2048)
                while (System.nanoTime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    host(String(packet.data, 0, packet.length, Charsets.US_ASCII))?.let { return it }
                }
            }
        } catch (_: IOException) {
            // no network, or multicast not allowed: nothing found
        }
        return null
    }

    /** The TV's address from an SSDP answer, or null when the answer is not from an LG TV's second-screen service. */
    internal fun host(response: String): String? {
        val headers = response.lineSequence()
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
            .toMap()
        if (headers["st"] != SERVICE && headers["usn"]?.contains(SERVICE) != true) return null
        val location = headers["location"] ?: return null
        return try {
            URI(location).host
        } catch (_: Exception) {
            null
        }
    }
}
