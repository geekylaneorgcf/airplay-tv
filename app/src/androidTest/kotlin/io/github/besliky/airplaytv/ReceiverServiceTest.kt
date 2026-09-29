package io.github.besliky.airplaytv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.besliky.airplaytv.core.NativeBridge
import io.github.besliky.airplaytv.service.Advertiser
import io.github.besliky.airplaytv.service.ReceiverService
import io.github.besliky.airplaytv.service.ReceiverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Starts the real foreground service and talks RTSP to the native receiver over
 * loopback, the way a sender's first request does.
 */
@RunWith(AndroidJUnit4::class)
class ReceiverServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitForPort(): Int {
        repeat(100) {
            val s = ReceiverState.current
            if (s.port > 0 && s.status != ReceiverState.Status.STARTING) return s.port
            Thread.sleep(100)
        }
        return ReceiverState.current.port
    }

    private fun waitForPublishedName(timeoutMs: Long, condition: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val name = ReceiverState.current.publishedName
            if (condition(name)) return name
            Thread.sleep(100)
        }
        return ReceiverState.current.publishedName
    }

    private fun rtsp(port: Int, request: String): Pair<String, ByteArray> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.soTimeout = 3000
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val input = socket.getInputStream()
            val raw = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var headEnd = -1
            var length = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                raw.write(buf, 0, n)
                val text = raw.toByteArray().toString(Charsets.ISO_8859_1)
                if (headEnd < 0) {
                    headEnd = text.indexOf("\r\n\r\n")
                    if (headEnd >= 0) {
                        length = Regex("Content-Length: (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                    }
                }
                if (headEnd >= 0 && raw.size() >= headEnd + 4 + length) break
            }
            val bytes = raw.toByteArray()
            val head = bytes.copyOfRange(0, headEnd + 4).toString(Charsets.ISO_8859_1)
            return head to bytes.copyOfRange(headEnd + 4, bytes.size)
        }
    }

    @Test
    fun serviceAnswersInfoRequests() {
        Settings(context).enabled = true
        ReceiverService.start(context)
        val port = waitForPort()
        assertTrue("receiver did not start", port > 0)

        val (head, body) = rtsp(port, "GET /info RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AirPlay/860.7.1\r\n\r\n")
        assertTrue(head, head.startsWith("RTSP/1.0 200 OK"))
        assertTrue(head.contains("CSeq: 1"))
        assertTrue(head.contains("application/x-apple-binary-plist"))
        val text = body.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("bplist00"))
        assertTrue(text.contains("AppleTV3,2"))
    }

    @Test
    fun txtRecordsAdvertiseMirroring() {
        ReceiverService.start(context)
        assertTrue(waitForPort() > 0)
        val txt = NativeBridge.nativeTxtRecords(false)
        assertNotNull(txt)
        val map = txt!!.toList().chunked(2).associate { it[0] to it[1] }
        assertEquals("AppleTV3,2", map["model"])
        assertEquals(64, map["pk"]?.length)
        val features = map["features"]!!.substringBefore(',').removePrefix("0x").toLong(16)
        assertTrue("screen mirroring bit", features and (1L shl 7) != 0L)
    }

    @Test
    fun malformedRequestsDoNotBreakTheReceiver() {
        ReceiverService.start(context)
        val port = waitForPort()
        repeat(5) {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 3000)
                s.getOutputStream().write(ByteArray(3000) { (it * 31).toByte() })
            }
        }
        val (head, _) = rtsp(port, "OPTIONS * RTSP/1.0\r\nCSeq: 5\r\n\r\n")
        assertTrue(head, head.startsWith("RTSP/1.0 200 OK"))
        assertTrue(head.contains("Public: SETUP"))
    }

    @Test
    fun takenNameIsReclaimedOnceItIsFree() {
        val settings = Settings(context)
        val originalName = settings.deviceName
        val name = "Name Test ${(1000..9999).random()}"
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val registered = CountDownLatch(1)
        val unregistered = CountDownLatch(1)
        val other = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = registered.countDown()
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = unregistered.countDown()
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = unregistered.countDown()
        }
        var otherActive = false
        try {
            settings.enabled = true
            ReceiverService.start(context)
            assertTrue(waitForPort() > 0)

            // Another receiver already uses the name when this one is renamed to it.
            val info = NsdServiceInfo().apply {
                serviceName = name
                serviceType = Advertiser.AIRPLAY_TYPE
                port = 7999
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, other)
            otherActive = true
            assertTrue("conflicting service was not registered", registered.await(10, TimeUnit.SECONDS))
            settings.deviceName = name

            val substitute = waitForPublishedName(20_000) { it.startsWith(name) }
            assertTrue("published as \"$substitute\"", substitute.startsWith(name))
            assertNotEquals(name, substitute)

            // Once the name is free again, the receiver takes it back.
            nsd.unregisterService(other)
            otherActive = false
            unregistered.await(10, TimeUnit.SECONDS)
            assertEquals(name, waitForPublishedName(40_000) { it == name })
        } finally {
            if (otherActive) nsd.unregisterService(other)
            settings.deviceName = originalName
        }
    }
}
