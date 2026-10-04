package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpMiniTest {

    @Test
    fun `a request line is read with its query`() {
        val request = HttpMini.parseHead("GET /?k=4821&x=a%20b HTTP/1.1\r\nHost: tv:7100\r\nAccept: */*")!!
        assertEquals("GET", request.method)
        assertEquals("/", request.path)
        assertEquals("4821", request.query["k"])
        assertEquals("a b", request.query["x"])
        assertEquals(0, request.contentLength)
    }

    @Test
    fun `the length of a body is read from its header, however it is spelled`() {
        val request = HttpMini.parseHead("POST /t?k=1 HTTP/1.1\r\ncontent-LENGTH: 12\r\nContent-Type: text/plain")!!
        assertEquals("POST", request.method)
        assertEquals("/t", request.path)
        assertEquals(12, request.contentLength)
    }

    @Test
    fun `something that is not HTTP is refused`() {
        assertNull(HttpMini.parseHead(""))
        assertNull(HttpMini.parseHead("hello"))
        assertNull(HttpMini.parseHead("GET /"))
        assertNull(HttpMini.parseHead("GET / SMTP/1.0"))
    }

    @Test
    fun `a bad length or a bad escape does no harm`() {
        assertEquals(0, HttpMini.parseHead("POST / HTTP/1.1\r\nContent-Length: x")!!.contentLength)
        assertEquals(0, HttpMini.parseHead("POST / HTTP/1.1\r\nContent-Length: -5")!!.contentLength)
        assertNotNull(HttpMini.parseHead("GET /?k=%zz HTTP/1.1"))
    }

    @Test
    fun `an answer carries its length`() {
        val text = String(HttpMini.response(200, "text/plain", "héllo"), Charsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 6\r\n"))
        assertTrue(text.endsWith("\r\n\r\nhéllo"))
        assertTrue(String(HttpMini.response(409, "text/plain", ""), Charsets.UTF_8).startsWith("HTTP/1.1 409 Conflict"))
    }
}
