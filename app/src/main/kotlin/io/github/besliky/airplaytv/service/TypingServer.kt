package io.github.besliky.airplaytv.service

import android.os.SystemClock
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * The page that types on the TV. While a text box is in focus on the TV (see [PhoneTypingService]) a short link with a four-digit code is
 * shown on the screen; the page behind it sends what is typed to this server, which puts it into the box. The server runs only while the
 * option is on and a text box has been in focus within the last [WINDOW_MS]; it refuses a request without the code shown on the screen, and
 * it never answers anything but that page and its text.
 */
object TypingServer {

    const val PORT = 7100
    private const val WINDOW_MS = 5 * 60_000L
    private const val MAX_BODY = 4096
    private const val MAX_HEAD = 8192

    private val random = SecureRandom()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var code = ""

    @Volatile
    private var validUntil = 0L

    /** A new code for a text box that has come into focus; the page works for [WINDOW_MS] from now. Returns the code. */
    fun open(): String {
        code = "%04d".format(random.nextInt(10_000))
        validUntil = SystemClock.elapsedRealtime() + WINDOW_MS
        start()
        return code
    }

    /** Ends the window: the code stops working. */
    fun close() {
        validUntil = 0L
        code = ""
    }

    fun stop() {
        close()
        val running = server ?: return
        server = null
        try {
            running.close()
        } catch (_: IOException) {
            // gone
        }
    }

    private fun valid(given: String?): Boolean =
        given != null && code.isNotEmpty() && given == code && SystemClock.elapsedRealtime() < validUntil

    @Synchronized
    private fun start() {
        if (server != null) return
        val socket = try {
            ServerSocket(PORT)
        } catch (e: IOException) {
            Log.w(SERVICE, "phone typing: cannot listen on $PORT: ${e.message}")
            return
        }
        server = socket
        Thread({ acceptLoop(socket) }, "typing-server").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            Thread({ serve(client) }, "typing-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 4000
            client.use {
                val input = it.getInputStream()
                val head = readHead(input) ?: return
                val request = HttpMini.parseHead(head) ?: return it.getOutputStream().write(HttpMini.response(400, "text/plain", "bad request"))
                val out = it.getOutputStream()
                if (!valid(request.query["k"])) {
                    out.write(HttpMini.response(403, "text/plain; charset=utf-8", "Open the link that the TV shows when a text box is open."))
                    return
                }
                when {
                    request.method == "GET" && request.path == "/" -> out.write(HttpMini.response(200, "text/html; charset=utf-8", page(request.query["k"].orEmpty())))
                    request.method == "POST" && request.path == "/t" -> {
                        if (request.contentLength > MAX_BODY) {
                            out.write(HttpMini.response(400, "text/plain", "too long"))
                            return
                        }
                        val body = readBody(input, request.contentLength)
                        val ok = PhoneTypingService.setText(String(body, Charsets.UTF_8))
                        out.write(HttpMini.response(if (ok) 200 else 409, "text/plain; charset=utf-8", if (ok) "ok" else "No text box is open on the TV"))
                    }
                    else -> out.write(HttpMini.response(404, "text/plain", "not found"))
                }
            }
        } catch (_: IOException) {
            // the phone went away
        }
    }

    private fun readHead(input: java.io.InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var tail = 0
        while (buffer.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            tail = if ((tail % 2 == 0 && b == '\r'.code) || (tail % 2 == 1 && b == '\n'.code)) tail + 1 else if (b == '\r'.code) 1 else 0
            if (tail == 4) return buffer.toString("UTF-8").removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun readBody(input: java.io.InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return body.copyOf(read)
    }

    /** The page: a box to type in; each change goes to the TV after a moment. */
    private fun page(key: String): String = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Type on the TV</title>
<style>body{font:18px -apple-system,sans-serif;margin:0;padding:20px;background:#111;color:#fff}
textarea{width:100%;box-sizing:border-box;height:9em;font-size:22px;border-radius:14px;border:0;padding:14px}
p{color:#aaa}#s{color:#7fd48a}</style></head><body>
<h2>Type on the TV</h2><textarea id="t" autofocus autocapitalize="off" autocorrect="off" spellcheck="false"></textarea>
<p id="s">What you type appears in the TV's text box. Press OK on the TV remote to search.</p>
<script>var t=document.getElementById('t'),s=document.getElementById('s'),h=null;
t.addEventListener('input',function(){clearTimeout(h);h=setTimeout(function(){
fetch('/t?k=$key',{method:'POST',headers:{'Content-Type':'text/plain; charset=utf-8'},body:t.value})
.then(function(r){return r.text().then(function(x){s.textContent=r.ok?'Sent':x;s.style.color=r.ok?'#7fd48a':'#ff8a80'})})
.catch(function(){s.textContent='The TV did not answer';s.style.color='#ff8a80'})},150)});</script></body></html>"""
}
