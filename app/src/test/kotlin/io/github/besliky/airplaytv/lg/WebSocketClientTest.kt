package io.github.besliky.airplaytv.lg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebSocketClientTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun read(frame: ByteArray, max: Int = WebSocketFrames.MAX_PAYLOAD) = WebSocketFrames.read(ByteArrayInputStream(frame), max)

    @Test
    fun `a client frame is encoded the way RFC 6455 shows it`() {
        // section 5.7: a single-frame masked text message "Hello"
        val mask = bytes(0x37, 0xfa, 0x21, 0x3d)
        val frame = WebSocketFrames.encode(WebSocketFrames.OP_TEXT, "Hello".toByteArray(), mask)
        assertArrayEquals(bytes(0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58), frame)
    }

    @Test
    fun `a server frame is read without a mask`() {
        val frame = read(bytes(0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f))
        assertTrue(frame.fin)
        assertEquals(WebSocketFrames.OP_TEXT, frame.opcode)
        assertEquals("Hello", String(frame.payload))
    }

    @Test
    fun `payloads of every length class survive a round trip`() {
        for (n in listOf(0, 1, 125, 126, 300, 65535, 65536, 70_000)) {
            val payload = ByteArray(n) { (it * 31).toByte() }
            val frame = read(WebSocketFrames.encode(WebSocketFrames.OP_TEXT, payload, bytes(1, 2, 3, 4)))
            assertArrayEquals("length $n", payload, frame.payload)
        }
    }

    @Test
    fun `the length bytes use the short, 16-bit and 64-bit forms`() {
        val mask = bytes(0, 0, 0, 0)
        assertEquals(0x80 or 125, WebSocketFrames.encode(1, ByteArray(125), mask)[1].toInt() and 0xFF)
        assertEquals(0x80 or 126, WebSocketFrames.encode(1, ByteArray(126), mask)[1].toInt() and 0xFF)
        assertEquals(0x80 or 127, WebSocketFrames.encode(1, ByteArray(65536), mask)[1].toInt() and 0xFF)
    }

    @Test
    fun `a frame larger than the limit is refused`() {
        try {
            read(bytes(0x81, 0x7E, 0x01, 0x00) + ByteArray(256), max = 100)
            fail("a frame over the limit must be refused")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `a truncated frame is an end of stream`() {
        try {
            read(bytes(0x81, 0x05, 0x48, 0x65))
            fail("a cut-off frame must not be returned")
        } catch (_: EOFException) {
            // expected
        }
    }

    @Test
    fun `the accept value for the RFC key is the one the RFC gives`() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketClient.expectedAccept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun `an upgrade answer is checked for the status and the accept value`() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val good = listOf("HTTP/1.1 101 Switching Protocols", "Upgrade: websocket", "sec-websocket-accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
        WebSocketClient.checkUpgrade(good, key) // does not throw
        for (bad in listOf(
            listOf("HTTP/1.1 400 Bad Request"),
            listOf("HTTP/1.1 101 Switching Protocols", "Sec-WebSocket-Accept: AAAA"),
            listOf("HTTP/1.1 101 Switching Protocols"),
            emptyList(),
        )) {
            try {
                WebSocketClient.checkUpgrade(bad, key)
                fail("must refuse $bad")
            } catch (_: IOException) {
                // expected
            }
        }
    }

    /** A socket with canned input and a record of what was written. */
    private class FakeSocket(incoming: ByteArray) : Socket() {
        val written = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(incoming)
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = written
        override fun setSoTimeout(timeout: Int) = Unit
        override fun close() = Unit
    }

    private fun client(vararg frames: ByteArray): Pair<WebSocketClient, FakeSocket> {
        val socket = FakeSocket(frames.fold(ByteArray(0)) { all, f -> all + f })
        return WebSocketClient.forTest(socket) to socket
    }

    private fun server(opcode: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
        require(payload.size < 126)
        return bytes((if (fin) 0x80 else 0) or opcode, payload.size) + payload
    }

    @Test
    fun `a message split over frames is put together, and a ping in between is answered`() {
        val (ws, socket) = client(
            server(WebSocketFrames.OP_TEXT, "Hel".toByteArray(), fin = false),
            server(WebSocketFrames.OP_PING, "p".toByteArray()),
            server(0x0, "lo".toByteArray()),
        )
        assertEquals("Hello", ws.receive(1000))
        val pong = socket.written.toByteArray()
        assertEquals(0x80 or WebSocketFrames.OP_PONG, pong[0].toInt() and 0xFF)
        assertEquals(0x80 or 1, pong[1].toInt() and 0xFF) // masked, one byte of payload
    }

    @Test
    fun `a close frame ends the wait`() {
        val (ws, _) = client(server(WebSocketFrames.OP_CLOSE, ByteArray(0)))
        try {
            ws.receive(1000)
            fail("a closed connection must not return a message")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `text sent is masked and in one final frame`() {
        val (ws, socket) = client()
        ws.send("hi")
        val frame = socket.written.toByteArray()
        assertEquals(0x81, frame[0].toInt() and 0xFF)
        assertTrue("the client must mask", frame[1].toInt() and 0x80 != 0)
        val decoded = WebSocketFrames.read(ByteArrayInputStream(frame))
        assertEquals("hi", String(decoded.payload))
        assertFalse(decoded.payload.isEmpty())
    }
}
