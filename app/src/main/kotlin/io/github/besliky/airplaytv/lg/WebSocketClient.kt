package io.github.besliky.airplaytv.lg

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket

/** WebSocket frames (RFC 6455), kept apart from the socket so the byte layout can be tested. */
internal object WebSocketFrames {
    const val OP_TEXT = 0x1
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    /** The largest message accepted from a TV; its answers are a few hundred bytes. */
    const val MAX_PAYLOAD = 1 shl 20

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** One frame from a client: final, and masked with [mask] (four bytes), as the protocol requires of clients. */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
        require(mask.size == 4) { "the mask is four bytes" }
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> out.write(0x80 or n)
            n < 65536 -> {
                out.write(0x80 or 126)
                out.write(n shr 8)
                out.write(n and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) out.write(((n.toLong() shr shift) and 0xFF).toInt())
            }
        }
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
        return out.toByteArray()
    }

    /** Reads one frame; a server's frames are not masked, but a masked one is handled too. */
    fun read(input: InputStream, maxPayload: Int = MAX_PAYLOAD): Frame {
        val b0 = input.readByte()
        val b1 = input.readByte()
        val masked = b1 and 0x80 != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = ((input.readByte() shl 8) or input.readByte()).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or input.readByte().toLong() }
        }
        if (length < 0 || length > maxPayload) throw IOException("a frame of $length bytes is too large")
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(length.toInt()).also { input.readFully(it) }
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return Frame(fin = b0 and 0x80 != 0, opcode = b0 and 0x0F, payload = payload)
    }

    private fun InputStream.readByte(): Int = read().also { if (it < 0) throw EOFException() }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = read(buffer, offset, buffer.size - offset)
            if (n < 0) throw EOFException()
            offset += n
        }
    }
}

/**
 * A small WebSocket client for a TV on the local network: text messages only, which is all the TV's remote
 * control protocol uses. It exists because Android has none built in and a TV's few messages do not justify a
 * library.
 */
class WebSocketClient private constructor(private val socket: Socket, private val input: InputStream) : LgTv.Connection {

    private val output = socket.getOutputStream()
    private val random = SecureRandom()

    @Synchronized
    override fun send(text: String) = write(WebSocketFrames.OP_TEXT, text.toByteArray(Charsets.UTF_8))

    @Synchronized
    private fun write(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also { random.nextBytes(it) }
        output.write(WebSocketFrames.encode(opcode, payload, mask))
        output.flush()
    }

    /** The next text message. Answers pings meanwhile; fails when the TV closes the connection or [timeoutMs] passes. */
    override fun receive(timeoutMs: Int): String {
        socket.soTimeout = timeoutMs
        val message = ByteArrayOutputStream()
        while (true) {
            val frame = WebSocketFrames.read(input)
            when (frame.opcode) {
                WebSocketFrames.OP_PING -> write(WebSocketFrames.OP_PONG, frame.payload)
                WebSocketFrames.OP_PONG -> Unit
                WebSocketFrames.OP_CLOSE -> throw IOException("the TV closed the connection")
                else -> {
                    message.write(frame.payload)
                    if (frame.fin) return message.toString("UTF-8")
                }
            }
        }
    }

    override fun close() {
        try {
            write(WebSocketFrames.OP_CLOSE, ByteArray(0))
        } catch (_: IOException) {
            // already gone
        }
        try {
            socket.close()
        } catch (_: IOException) {
            // already gone
        }
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_HEADERS = 8192

        /**
         * Opens a connection to [host]. Over TLS ([secure]) the certificate is checked by [trust]: a failed check
         * surfaces as [TvTrust.Untrusted] carrying the certificate that was shown, so that it can be put to the
         * owner; nothing is sent to a TV whose certificate was not accepted.
         */
        fun connect(host: String, port: Int, secure: Boolean, path: String, trust: TvTrust, timeoutMs: Int = 6000): WebSocketClient {
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                raw.soTimeout = timeoutMs
                val socket: Socket = if (secure) {
                    val tls = trust.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                    try {
                        tls.startHandshake()
                    } catch (e: IOException) {
                        throw trust.explain(e)
                    }
                    tls
                } else {
                    raw
                }
                val input = BufferedInputStream(socket.getInputStream())
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
                socket.getOutputStream().apply {
                    write(
                        ("GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII),
                    )
                    flush()
                }
                checkUpgrade(readHeaders(input), key)
                return WebSocketClient(socket, input)
            } catch (e: IOException) {
                try {
                    raw.close()
                } catch (_: IOException) {
                    // nothing to do
                }
                throw e
            }
        }

        /** A client over an already open [socket], for the tests. */
        internal fun forTest(socket: Socket) = WebSocketClient(socket, BufferedInputStream(socket.getInputStream()))

        /** The value a server must answer with for [key]. */
        internal fun expectedAccept(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))

        /** Throws unless [headers] (the server's answer to the upgrade request) is a proper "101 Switching Protocols". */
        internal fun checkUpgrade(headers: List<String>, key: String) {
            val status = headers.firstOrNull() ?: throw IOException("no answer to the upgrade request")
            if (!status.contains(" 101")) throw IOException("the TV did not upgrade the connection: $status")
            val accept = headers.drop(1)
                .firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            if (accept != expectedAccept(key)) throw IOException("the TV's upgrade answer did not match")
        }

        private fun readHeaders(input: InputStream): List<String> {
            val bytes = ByteArrayOutputStream()
            var endOfHeaders = 0
            while (bytes.size() < MAX_HEADERS) {
                val b = input.read()
                if (b < 0) throw EOFException()
                bytes.write(b)
                // CR LF CR LF ends the headers
                endOfHeaders = when {
                    b == 13 && (endOfHeaders == 0 || endOfHeaders == 2) -> endOfHeaders + 1
                    b == 10 && (endOfHeaders == 1 || endOfHeaders == 3) -> endOfHeaders + 1
                    b == 13 -> 1
                    else -> 0
                }
                if (endOfHeaders == 4) break
            }
            if (endOfHeaders != 4) throw IOException("the TV's answer has no end of headers")
            return bytes.toString("US-ASCII").split("\r\n").filter { it.isNotEmpty() }
        }
    }
}
