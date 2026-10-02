package io.github.besliky.airplaytv.lg

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-lived session played against a scripted TV, and the parsing of what a TV says. */
class LgSessionTest {

    /** A TV whose messages arrive through a queue, so that reading blocks as a real socket does. */
    private class ScriptedTv(private val reply: (JSONObject, ScriptedTv) -> Unit) : LgTv.Connection {
        private val inbox = LinkedBlockingQueue<String>()
        val sent = ArrayList<JSONObject>()
        @Volatile var closed = false

        fun say(text: String) = inbox.add(text)

        @Synchronized
        override fun send(text: String) {
            if (closed) throw IOException("closed")
            val message = JSONObject(text)
            sent += message
            reply(message, this)
        }

        override fun receive(timeoutMs: Int): String {
            if (closed) throw IOException("closed")
            return inbox.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("silent TV")
        }

        override fun close() {
            closed = true
        }
    }

    private fun session(tv: ScriptedTv) = LgSession(tv, "KEY").also { it.start() }

    @Test
    fun `a request gets the answer that carries its id`() {
        val tv = ScriptedTv { message, tv ->
            if (message.optString("uri") == "ssap://audio/getVolume") {
                tv.say("""{"type":"response","id":"${message.getString("id")}","payload":{"returnValue":true,"volumeStatus":{"volume":17,"muteStatus":false,"adjustVolume":true}}}""")
            }
        }
        session(tv).use { s ->
            val answer = s.request("ssap://audio/getVolume")
            assertTrue(LgSession.succeeded(answer))
            assertEquals(LgFacts.Volume(17, muted = false, adjustable = true), LgFacts.volume(answer!!))
        }
    }

    @Test
    fun `a TV that does not answer gives null after the timeout`() {
        session(ScriptedTv { _, _ -> }).use { s ->
            assertNull(s.request("ssap://audio/getVolume", timeoutMs = 80))
        }
    }

    @Test
    fun `an error answer is not a success`() {
        val tv = ScriptedTv { message, tv ->
            tv.say("""{"type":"error","id":"${message.getString("id")}","error":"404 no such service"}""")
        }
        session(tv).use { s -> assertFalse(LgSession.succeeded(s.request("ssap://nothing/here"))) }
    }

    @Test
    fun `a subscription hears every message with its id`() {
        val got = LinkedBlockingQueue<String?>()
        var subscriptionId = ""
        val tv = ScriptedTv { message, _ -> if (message.optString("type") == "subscribe") subscriptionId = message.getString("id") }
        session(tv).use { s ->
            assertTrue(s.subscribe("ssap://com.webos.applicationManager/getForegroundAppInfo") { got.add(LgFacts.foregroundApp(it)) })
            tv.say("""{"type":"response","id":"$subscriptionId","payload":{"appId":"com.webos.app.hdmi1","returnValue":true}}""")
            tv.say("""{"type":"response","id":"$subscriptionId","payload":{"appId":"com.webos.app.hdmi3","returnValue":true}}""")
            assertEquals("com.webos.app.hdmi1", got.poll(2, TimeUnit.SECONDS))
            assertEquals("com.webos.app.hdmi3", got.poll(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `closing the connection tells the owner of the session`() {
        val tv = ScriptedTv { _, _ -> }
        val closed = CountDownLatch(1)
        val s = session(tv)
        s.onClosed = { closed.countDown() }
        tv.close()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertTrue(s.closed)
        assertNull(s.request("ssap://audio/getVolume"))
        assertFalse(s.subscribe("ssap://x") {})
    }

    @Test
    fun `an input is an HDMI app, and a TV in front of anything else is not on an input`() {
        assertEquals("HDMI_1", LgFacts.inputIdOf("com.webos.app.hdmi1"))
        assertEquals("HDMI_4", LgFacts.inputIdOf("com.webos.app.hdmi4"))
        assertNull(LgFacts.inputIdOf("com.webos.app.home"))
        assertNull(LgFacts.inputIdOf(null))
    }

    @Test
    fun `power states say whether a picture is shown`() {
        assertTrue(LgFacts.showsPicture("Active"))
        assertTrue(LgFacts.showsPicture(null))
        assertFalse(LgFacts.showsPicture("Active Standby"))
        assertFalse(LgFacts.showsPicture("Screen Off"))
        assertFalse(LgFacts.showsPicture("Suspend"))
        assertEquals("Screen Off", LgFacts.powerState(JSONObject("""{"payload":{"state":"Screen Off"}}""")))
        assertNull(LgFacts.powerState(JSONObject("""{"payload":{}}""")))
    }

    @Test
    fun `volume comes in two shapes and a fixed output is not adjustable`() {
        assertEquals(LgFacts.Volume(9, muted = true, adjustable = true), LgFacts.volume(JSONObject("""{"payload":{"volume":9,"muted":true}}""")))
        assertEquals(
            LgFacts.Volume(30, muted = false, adjustable = false),
            LgFacts.volume(JSONObject("""{"payload":{"volumeStatus":{"volume":30,"muteStatus":false,"adjustVolume":false}}}""")),
        )
        assertNull(LgFacts.volume(JSONObject("""{"payload":{"returnValue":true}}""")))
    }

    @Test
    fun `hardware addresses come from the wired and the wireless info`() {
        val message = JSONObject(
            """{"payload":{"wiredInfo":{"macAddress":"3C:CD:93:1A:2B:4C"},"wifiInfo":{"macAddress":"3c:cd:93:1a:2b:4d"},"returnValue":true}}""",
        )
        assertEquals(listOf("3c:cd:93:1a:2b:4c", "3c:cd:93:1a:2b:4d"), LgFacts.macAddresses(message))
        assertEquals(emptyList<String>(), LgFacts.macAddresses(JSONObject("""{"payload":{}}""")))
        assertNotNull(LgFacts.macAddresses(JSONObject("{}")))
    }
}
