package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Test

class DacpClientTest {

    @Test
    fun `the status code is read from the status line`() {
        assertEquals(204, DacpClient.statusCode("HTTP/1.1 204 No Content"))
        assertEquals(200, DacpClient.statusCode("HTTP/1.0 200 OK"))
        assertEquals(404, DacpClient.statusCode(" HTTP/1.1 404 Not Found "))
        assertEquals(500, DacpClient.statusCode("HTTP/1.1 500"))
    }

    @Test
    fun `no usable status line is minus one`() {
        assertEquals(-1, DacpClient.statusCode(null))
        assertEquals(-1, DacpClient.statusCode(""))
        assertEquals(-1, DacpClient.statusCode("garbage"))
        assertEquals(-1, DacpClient.statusCode("HTTP/1.1 abc"))
    }

    @Test
    fun `a seek is a setproperty of the playing time in milliseconds`() {
        assertEquals("setproperty?dacp.playingtime=93000", DacpClient.seekTo(93_000))
        assertEquals("setproperty?dacp.playingtime=0", DacpClient.seekTo(0))
    }
}
