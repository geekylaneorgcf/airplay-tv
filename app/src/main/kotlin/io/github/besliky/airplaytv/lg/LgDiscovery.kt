package io.github.besliky.airplaytv.lg

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
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

    /**
     * The address of the first LG TV that answers within [timeoutMs], or null. Blocking. The search goes to the
     * multicast group and, in case the network or this device does not carry multicast, straight to every address
     * of the local network (a TV answers a search sent to it alone just the same).
     */
    fun find(timeoutMs: Int = 5000): String? {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                val group = InetAddress.getByName(GROUP)
                val neighbours = localTargets()
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                val resendAt = System.nanoTime() + timeoutMs * 400_000L
                var resent = false
                fun search() {
                    sendQuietly(socket, group)
                    for (target in neighbours) sendQuietly(socket, target)
                }
                search()
                val buffer = ByteArray(2048)
                while (System.nanoTime() < deadline) {
                    if (!resent && System.nanoTime() > resendAt) {
                        resent = true
                        search()
                    }
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
            // no network: nothing found
        }
        return null
    }

    private fun sendQuietly(socket: DatagramSocket, to: InetAddress) {
        try {
            socket.send(DatagramPacket(SEARCH, SEARCH.size, to, PORT))
        } catch (_: IOException) {
            // this address cannot be reached from here; the others may be
        }
    }

    /** Every other address of the local network(s) this device is on (IPv4, private ranges, Wi-Fi or Ethernet). */
    private fun localTargets(): List<InetAddress> {
        val found = LinkedHashSet<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nic in interfaces.asSequence()) {
                // Wi-Fi Direct (p2p) is a private link to one device, not the home network
                if (!nic.isUp || nic.isLoopback || nic.name.startsWith("p2p")) continue
                for (a in nic.interfaceAddresses) {
                    val address = a.address as? Inet4Address ?: continue
                    if (!address.isSiteLocalAddress) continue
                    found += subnetHosts(address.address, a.networkPrefixLength.toInt())
                }
            }
        } catch (_: IOException) {
            // fall back to the multicast search alone
        }
        return found.toList()
    }

    /**
     * The other addresses of the network that [address] (four bytes) is on, with a prefix of [prefix] bits; networks
     * larger than a /24 are cut down to the /24 around [address], so a search stays a few hundred packets.
     */
    internal fun subnetHosts(address: ByteArray, prefix: Int): List<InetAddress> {
        val bits = prefix.coerceIn(24, 30)
        val mask = (-1 shl (32 - bits))
        val own = address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val network = own and mask
        val broadcast = network or mask.inv()
        val result = ArrayList<InetAddress>()
        for (ip in (network + 1) until broadcast) {
            if (ip == own) continue
            result += InetAddress.getByAddress(byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte()))
        }
        return result
    }

    /**
     * What a person typed as the TV's address, made ready to use: a dotted IPv4 address, or a host name (as
     * "LGwebOSTV.local"), with any scheme, port or path pasted along with it removed. Null when it is neither.
     */
    fun cleanAddress(raw: String): String? {
        val bare = raw.trim().lowercase().substringAfter("://").substringBefore('/').substringBefore(':')
        if (bare.isEmpty() || bare.length > 253) return null
        val parts = bare.split('.')
        if (parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            return bare.takeIf { parts.size == 4 && parts.all { p -> p.length <= 3 && p.toInt() in 0..255 } }
        }
        return bare.takeIf { host -> host.all { it.isLetterOrDigit() || it == '.' || it == '-' } && !host.startsWith('-') && !host.startsWith('.') && !host.endsWith('.') }
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
