package io.github.besliky.airplaytv.lg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LgDiscoveryTest {

    // what an LG TV answered to the search (the address is a private one)
    private val answer = "HTTP/1.1 200 OK\r\n" +
        "Cache-Control: max-age=1800\r\n" +
        "Location: http://192.168.1.86:1550/\r\n" +
        "Server: WebOS/4.1.0 UPnP/1.0\r\n" +
        "USN: uuid:00000000-0000-0000-0000-000000000000::urn:lge-com:service:webos-second-screen:1\r\n" +
        "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"

    @Test
    fun `the TV's address is read from its answer`() {
        assertEquals("192.168.1.86", LgDiscovery.host(answer))
    }

    @Test
    fun `header names are not case sensitive`() {
        assertEquals("10.1.2.3", LgDiscovery.host(answer.replace("Location:", "LOCATION:").replace("192.168.1.86", "10.1.2.3")))
    }

    @Test
    fun `an answer from some other service is ignored`() {
        val other = answer.replace("urn:lge-com:service:webos-second-screen:1", "urn:schemas-upnp-org:device:MediaRenderer:1")
        assertNull(LgDiscovery.host(other))
    }

    @Test
    fun `an answer without an address is ignored`() {
        assertNull(LgDiscovery.host(answer.replace(Regex("Location:[^\r]*\r\n"), "")))
        assertNull(LgDiscovery.host(""))
        assertNull(LgDiscovery.host("garbage"))
    }

    @Test
    fun `a search covers the other addresses of the local network`() {
        val hosts = LgDiscovery.subnetHosts(byteArrayOf(192.toByte(), 168.toByte(), 1, 76), 24).map { it.hostAddress }
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.76" in hosts)
    }

    @Test
    fun `a network larger than a slash 24 is cut down to the slash 24 around the address`() {
        val hosts = LgDiscovery.subnetHosts(byteArrayOf(10, 0, 5, 9), 16).map { it.hostAddress }
        assertEquals(253, hosts.size)
        assertEquals("10.0.5.1", hosts.first())
    }

    @Test
    fun `a small network is searched whole`() {
        val hosts = LgDiscovery.subnetHosts(byteArrayOf(192.toByte(), 168.toByte(), 1, 5), 29).map { it.hostAddress }
        assertEquals(listOf("192.168.1.1", "192.168.1.2", "192.168.1.3", "192.168.1.4", "192.168.1.6"), hosts)
    }

    @Test
    fun `a typed address is cleaned up and checked`() {
        assertEquals("192.168.1.86", LgDiscovery.cleanAddress(" 192.168.1.86 "))
        assertEquals("192.168.1.86", LgDiscovery.cleanAddress("wss://192.168.1.86:3001/"))
        assertEquals("lgwebostv.local", LgDiscovery.cleanAddress("LGwebOSTV.local"))
        assertNull(LgDiscovery.cleanAddress(""))
        assertNull(LgDiscovery.cleanAddress("192.168.1.300"))
        assertNull(LgDiscovery.cleanAddress("not an address"))
        assertNull(LgDiscovery.cleanAddress("192.168.1"))
    }
}
