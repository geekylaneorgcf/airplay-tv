package io.github.besliky.airplaytv.lg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WolTest {

    private val mac = byteArrayOf(0x3c, 0xcd.toByte(), 0x93.toByte(), 0x1a, 0x2b, 0x4c)

    @Test
    fun `a hardware address is read with colons, dashes or none`() {
        assertArrayEquals(mac, Wol.parseMac("3c:cd:93:1a:2b:4c"))
        assertArrayEquals(mac, Wol.parseMac("3C-CD-93-1A-2B-4C"))
        assertArrayEquals(mac, Wol.parseMac(" 3ccd931a2b4c "))
        assertEquals("3c:cd:93:1a:2b:4c", Wol.format(mac))
    }

    @Test
    fun `things that are not a hardware address are refused`() {
        assertNull(Wol.parseMac(""))
        assertNull(Wol.parseMac("3c:cd:93:1a:2b"))
        assertNull(Wol.parseMac("zz:cd:93:1a:2b:4c"))
        assertNull(Wol.parseMac("00:00:00:00:00:00"))
        assertNull(Wol.parseMac("ff:ff:ff:ff:ff:ff"))
    }

    @Test
    fun `the magic packet is six 0xFF and the address sixteen times`() {
        val packet = Wol.magicPacket(mac)
        assertEquals(102, packet.size)
        for (i in 0 until 6) assertEquals(0xFF.toByte(), packet[i])
        for (i in 0 until 16) assertArrayEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test
    fun `the broadcast address of a network follows from its prefix`() {
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 255.toByte()), Wol.broadcastOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 76), 24))
        assertArrayEquals(byteArrayOf(10, 0, 3, 255.toByte()), Wol.broadcastOf(byteArrayOf(10, 0, 2, 9), 23))
        assertNull(Wol.broadcastOf(byteArrayOf(10, 0, 2, 9), 0))
        assertNull(Wol.broadcastOf(ByteArray(16), 64))
    }

    @Test
    fun `the neighbour table gives the address of a device by its IP`() {
        val table = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.1.1      0x1         0x2         b8:27:eb:00:11:22     *        wlan0
            192.168.1.86     0x1         0x2         3C:CD:93:1A:2B:4C     *        wlan0
            192.168.1.99     0x1         0x0         00:00:00:00:00:00     *        wlan0
        """.trimIndent()
        assertEquals("3c:cd:93:1a:2b:4c", ArpTable.macFor("192.168.1.86", table))
        assertNull(ArpTable.macFor("192.168.1.99", table))
        assertNull(ArpTable.macFor("192.168.1.5", table))
    }
}
