package io.github.besliky.airplaytv.lg

import org.junit.Assert.assertEquals
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
}
