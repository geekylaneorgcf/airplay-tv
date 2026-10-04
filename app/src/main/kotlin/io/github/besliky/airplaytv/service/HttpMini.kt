package io.github.besliky.airplaytv.service

import java.net.URLDecoder

/** Just enough of HTTP for the page that types on the TV: reading one request and writing one answer. Pure, so it is tested without a socket. */
object HttpMini {

    class Request(val method: String, val path: String, val query: Map<String, String>, val contentLength: Int)

    /** The request line and headers in [head] (everything before the blank line), or null when it is not an HTTP request. */
    fun parseHead(head: String): Request? {
        val lines = head.split("\r\n")
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) return null
        val target = parts[1]
        val question = target.indexOf('?')
        val path = if (question < 0) target else target.substring(0, question)
        val query = HashMap<String, String>()
        if (question >= 0) {
            for (pair in target.substring(question + 1).split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = decode(if (eq < 0) pair else pair.substring(0, eq))
                val value = if (eq < 0) "" else decode(pair.substring(eq + 1))
                query[key] = value
            }
        }
        var length = 0
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                length = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        return Request(parts[0].uppercase(), path, query, length.coerceAtLeast(0))
    }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (_: IllegalArgumentException) {
        text
    }

    fun response(status: Int, contentType: String, body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.UTF_8) + bytes
    }
}
