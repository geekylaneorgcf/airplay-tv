package io.github.besliky.airplaytv.lg

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.io.IOException

/**
 * Wakes a TV that is off over the network: the "magic packet" of Wake-on-LAN is six bytes of 0xFF and then the TV's hardware
 * address sixteen times, sent as a broadcast. An LG TV answers it when its own setting "Turn on via Wi-Fi" (under General, Devices,
 * External Devices, TV On With Mobile) is on, which is the owner's to switch on, on the TV.
 */
object Wol {

    /** The hardware address in [text] (`aa:bb:cc:dd:ee:ff`, with `-` or without separators), or null when it is not one. */
    fun parseMac(text: String): ByteArray? {
        val hex = text.trim().replace(":", "").replace("-", "")
        if (hex.length != 12) return null
        val bytes = ByteArray(6)
        for (i in 0 until 6) {
            bytes[i] = (hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        }
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 0xFF.toByte() }) return null
        return bytes
    }

    fun format(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }

    /** The packet for [mac]: 6 x 0xFF, then [mac] 16 times (102 bytes). */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "a hardware address has six bytes" }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(mac, 0, packet, 6 + i * 6, 6)
        return packet
    }

    /** The broadcast address of the network [address] is on, for a prefix of [prefixLength] bits; null for anything but IPv4. */
    fun broadcastOf(address: ByteArray, prefixLength: Int): ByteArray? {
        if (address.size != 4 || prefixLength !in 1..30) return null
        val mask = (-1 shl (32 - prefixLength))
        val ip = ((address[0].toInt() and 0xFF) shl 24) or ((address[1].toInt() and 0xFF) shl 16) or
            ((address[2].toInt() and 0xFF) shl 8) or (address[3].toInt() and 0xFF)
        val broadcast = (ip and mask) or mask.inv()
        return byteArrayOf((broadcast ushr 24).toByte(), (broadcast ushr 16).toByte(), (broadcast ushr 8).toByte(), broadcast.toByte())
    }

    /**
     * Sends the packet for each of [macs] to each of [broadcasts] (and to the limited broadcast address) on ports 9 and 7.
     * True when at least one packet went out. Blocking, but quick: run it off the main thread.
     */
    fun send(macs: List<ByteArray>, broadcasts: List<ByteArray>): Boolean {
        val targets = (broadcasts + listOf(byteArrayOf(-1, -1, -1, -1))).distinctBy { it.toList() }
        var sent = false
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (mac in macs) {
                    val packet = magicPacket(mac)
                    for (target in targets) {
                        for (port in PORTS) {
                            try {
                                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByAddress(target), port))
                                sent = true
                            } catch (_: IOException) {
                                // that network will not take a broadcast; the others may
                            }
                        }
                    }
                }
            }
        } catch (_: IOException) {
            return sent
        }
        return sent
    }

    private val PORTS = intArrayOf(9, 7)
}

/** The kernel's table of neighbours (`/proc/net/arp`), which tells the hardware address of a device that answered lately. */
object ArpTable {

    /** The hardware address of [ip] in [table], or null when it is not there or is incomplete (all zeroes). */
    fun macFor(ip: String, table: String): String? {
        for (line in table.lines().drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4 || parts[0] != ip) continue
            val mac = parts[3]
            if (Wol.parseMac(mac) != null) return mac.lowercase()
        }
        return null
    }

    fun read(): String = try {
        java.io.File("/proc/net/arp").readText()
    } catch (_: Exception) {
        ""
    }
}
