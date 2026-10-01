package io.github.besliky.airplaytv.lg

import java.io.IOException
import java.net.SocketTimeoutException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conversation with a TV, played against a scripted one. */
class LgTvTest {

    /** A TV that answers each message with what [reply] says; an empty inbox is a silent TV. */
    private class FakeTv(private val reply: (JSONObject) -> List<String>) : LgTv.Connection {
        val sent = ArrayList<String>()
        private val inbox = ArrayDeque<String>()
        var closed = false

        override fun send(text: String) {
            sent += text
            if (text.startsWith("{")) inbox.addAll(reply(JSONObject(text)))
        }

        override fun receive(timeoutMs: Int): String = inbox.removeFirstOrNull() ?: throw SocketTimeoutException("silent TV")

        override fun close() {
            closed = true
        }
    }

    private val hello = """{"type":"hello","payload":{"deviceType":"tv"}}"""
    private val sysInfo = """{"type":"response","id":"get_sys_info","payload":{"returnValue":true}}"""
    private val prompt = """{"type":"response","id":"register_0","payload":{"pairingType":"PROMPT","returnValue":true}}"""
    private fun registered(key: String) = """{"type":"registered","id":"register_0","payload":{"client-key":"$key"}}"""
    private val socketReply = """{"type":"response","id":"sock","payload":{"returnValue":true,"socketPath":"wss://192.168.1.86:3001/resources/abc/netinput.pointer.sock"}}"""

    /** A TV that pairs after a prompt (or at once when the client already has [knownKey]) and hands out a button socket. */
    private fun friendlyTv(newKey: String = "NEW-KEY", knownKey: String? = null) = FakeTv { message ->
        when (message.optString("type")) {
            "hello" -> listOf(hello)
            "request" -> when (message.optString("id")) {
                "get_sys_info" -> listOf(sysInfo)
                "sock" -> listOf(socketReply)
                else -> emptyList()
            }
            "register" -> {
                val given = message.getJSONObject("payload").optString("client-key", "")
                if (knownKey != null && given == knownKey) listOf(registered(knownKey)) else listOf(prompt, registered(newKey))
            }
            else -> emptyList()
        }
    }

    private class Opened(val host: String, val port: Int, val secure: Boolean, val path: String)

    private fun connector(main: FakeTv, buttons: FakeTv = FakeTv { emptyList() }, opened: MutableList<Opened> = ArrayList()) =
        LgTv.Connector { host, port, secure, path ->
            opened += Opened(host, port, secure, path)
            if (path == "/") main else buttons
        }

    @Test
    fun `pairing waits for the prompt on the TV and returns the key it gives`() {
        val tv = friendlyTv(newKey = "KEY-123")
        var prompts = 0
        val outcome = LgTv(connector(tv), settleMs = 0).press("192.168.1.86", null, null) { prompts++ }
        assertEquals(LgTv.Outcome.Done("KEY-123"), outcome)
        assertEquals("the owner is told once", 1, prompts)
        assertTrue(tv.closed)
    }

    @Test
    fun `a paired client skips the prompt and presses the button on the second socket`() {
        val main = friendlyTv(knownKey = "KEY-123")
        val buttons = FakeTv { emptyList() }
        val opened = ArrayList<Opened>()
        var prompts = 0
        val outcome = LgTv(connector(main, buttons, opened), settleMs = 0).press("192.168.1.86", "KEY-123", LgTv.MENU) { prompts++ }
        assertEquals(LgTv.Outcome.Done("KEY-123"), outcome)
        assertEquals(0, prompts)
        assertEquals(listOf("type:button\nname:MENU\n\n"), buttons.sent)
        assertEquals("the main socket, then the button socket", 2, opened.size)
        val socket = opened[1]
        assertEquals("192.168.1.86", socket.host)
        assertEquals(3001, socket.port)
        assertTrue(socket.secure)
        assertEquals("/resources/abc/netinput.pointer.sock", socket.path)
        assertTrue(buttons.closed && main.closed)
    }

    @Test
    fun `the messages go in the order the TV needs`() {
        val main = friendlyTv(knownKey = "K")
        LgTv(connector(main), settleMs = 0).press("h", "K", LgTv.MENU)
        val types = main.sent.map { JSONObject(it).let { m -> m.optString("type") + ":" + m.optString("id") } }
        assertEquals(listOf("hello:hello", "request:get_sys_info", "register:register_0", "request:sock"), types)
    }

    @Test
    fun `declining the prompt is reported as declined`() {
        val tv = FakeTv { m ->
            when (m.optString("type")) {
                "hello" -> listOf(hello)
                "request" -> listOf(sysInfo)
                "register" -> listOf(prompt, """{"type":"error","id":"register_0","error":"403 User denied access"}""")
                else -> emptyList()
            }
        }
        assertEquals(LgTv.Outcome.Declined, LgTv(connector(tv), settleMs = 0).press("h", null, null))
    }

    @Test
    fun `a prompt nobody answers is reported as no answer`() {
        val tv = FakeTv { m ->
            when (m.optString("type")) {
                "hello" -> listOf(hello)
                "request" -> listOf(sysInfo)
                "register" -> listOf(prompt)
                else -> emptyList()
            }
        }
        assertEquals(LgTv.Outcome.NoAnswer, LgTv(connector(tv), settleMs = 0).press("h", null, null))
    }

    @Test
    fun `an untrusted certificate is reported and plain text is never used instead`() {
        val cert = TvTrust.decode(CERT)!!
        val calls = ArrayList<Opened>()
        val outcome = LgTv(
            LgTv.Connector { host, port, secure, path ->
                calls += Opened(host, port, secure, path)
                throw TvTrust.Untrusted(cert, IOException("not trusted"))
            },
        ).press("h", null, null)
        assertTrue(outcome is LgTv.Outcome.Untrusted)
        assertEquals(cert, (outcome as LgTv.Outcome.Untrusted).error.certificate)
        assertEquals("one try over TLS, no fallback", 1, calls.size)
        assertTrue(calls[0].secure)
    }

    @Test
    fun `a TV without TLS is spoken to in plain text on the old port`() {
        val tv = friendlyTv(knownKey = "K")
        val calls = ArrayList<Opened>()
        val outcome = LgTv(
            LgTv.Connector { host, port, secure, path ->
                calls += Opened(host, port, secure, path)
                if (secure) throw IOException("connection reset") else tv
            },
        ).press("h", "K", null)
        assertEquals(LgTv.Outcome.Done("K"), outcome)
        assertEquals(listOf(3001 to true, 3000 to false), calls.map { it.port to it.secure })
    }

    @Test
    fun `a TV that cannot be reached is reported as unreachable`() {
        val outcome = LgTv(LgTv.Connector { _, _, _, _ -> throw IOException("no route to host") }).press("h", null, null)
        assertTrue(outcome is LgTv.Outcome.Unreachable)
    }

    @Test
    fun `a TV that refuses the button socket is a failure`() {
        val tv = FakeTv { m ->
            when (m.optString("type")) {
                "hello" -> listOf(hello)
                "request" -> if (m.optString("id") == "sock") {
                    listOf("""{"type":"error","id":"sock","error":"401 insufficient permissions","payload":{}}""")
                } else {
                    listOf(sysInfo)
                }
                "register" -> listOf(registered("K"))
                else -> emptyList()
            }
        }
        assertTrue(LgTv(connector(tv), settleMs = 0).press("h", "K", LgTv.MENU) is LgTv.Outcome.Failed)
    }

    @Test
    fun `a TV that falls silent is a failure and the connection is closed`() {
        val tv = FakeTv { emptyList() }
        assertTrue(LgTv(connector(tv), settleMs = 0).press("h", null, null) is LgTv.Outcome.Failed)
        assertTrue(tv.closed)
    }

    @Test
    fun `the registration message asks to be prompted and carries the key only when there is one`() {
        val without = JSONObject(LgTv.registerMessage(null))
        assertEquals("register", without.getString("type"))
        val payload = without.getJSONObject("payload")
        assertEquals("PROMPT", payload.getString("pairingType"))
        assertFalse(payload.getBoolean("forcePairing"))
        assertFalse(payload.has("client-key"))
        val permissions = payload.getJSONObject("manifest").getJSONArray("permissions")
        val all = (0 until permissions.length()).map { permissions.getString(it) }
        assertTrue("CONTROL_MOUSE_AND_KEYBOARD" in all && "LAUNCH" in all)

        assertEquals("KEY", JSONObject(LgTv.registerMessage("KEY")).getJSONObject("payload").getString("client-key"))
        assertFalse(JSONObject(LgTv.registerMessage("")).getJSONObject("payload").has("client-key"))
    }

    @Test
    fun `the button socket address is read in both forms`() {
        val secure = LgTv.parseSocketPath("wss://192.168.1.86:3001/resources/abc/netinput.pointer.sock", null)
        assertNotNull(secure)
        assertEquals(Triple("192.168.1.86", 3001, true), Triple(secure!!.host, secure.port, secure.secure))
        assertEquals("/resources/abc/netinput.pointer.sock", secure.path)

        val plain = LgTv.parseSocketPath("ws://10.0.0.5/resources/x?y=1", null)!!
        assertEquals(Triple("10.0.0.5", 3000, false), Triple(plain.host, plain.port, plain.secure))
        assertEquals("/resources/x?y=1", plain.path)
    }

    @Test
    fun `an unreadable button socket address is not guessed at`() {
        assertNull(LgTv.parseSocketPath("not a url", null))
        assertNull(LgTv.parseSocketPath("http://192.168.1.86/x", null))
        assertNull(LgTv.parseSocketPath("", null))
    }

    private companion object {
        // a throwaway self-signed certificate, only here to have something for an Untrusted to carry
        const val CERT = "MIIDGzCCAgOgAwIBAgIUAaVdkXK3XRypazMWIYoHfQ3glI0wDQYJKoZIhvcNAQELBQAwHDEaMBgGA1UEAwwRQWlyUGxheSBUViB0ZXN0IGEwIBcNMjYxMDAxMTM1MTM4WhgPMjEyNjA5MDcxMzUxMzhaMBwxGjAYBgNVBAMMEUFpclBsYXkgVFYgdGVzdCBhMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqlb2G/C8XFGIG/0/w+2LWvHPZ3S+AF4GnRcaO9tLx3xzVQAONtAhekdFrAPkHTJa+5MY6QdFWCuI3qL+cl4OrfC44wUzjGgEHheq+3kYyVC+cI0q+u9kwN0dV4KnYHltp7dGx8BrYmgIJ8dUFkl2pLxTRBUdsbaCzx6vO7cHonIiBGO5N+7VeFji+90hwZnL9VSOIIQvREk7OGT8bGe0s2astngua2zNMJX40Gf/0VUeXqDP1eb4TOiBQ2ILmeM9jDfWyg+VyyOI7Qa4VlqMDCyMVpArEUSAUQkIaOMRSfOaJN2otGEMqEgmMPbOZ/l+IfFTyizVt2aO4Sw7Lif06QIDAQABo1MwUTAdBgNVHQ4EFgQUcvJoUCJJPCoFdUC+tSNbYzUMURcwHwYDVR0jBBgwFoAUcvJoUCJJPCoFdUC+tSNbYzUMURcwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAqPaSv6SLCzqYkAHKmKQHI3OzmaOJrP83BQ74rK3KV2/2v1NQA680h6L8CEWxUdd3tiCkwiAOJwwxL/iKAQ4vYR7WG6budTLSsqs9bt+kz3ix/HIler+2A0V8nFabCNfU9z+FTnRGqOduJ6jCfk/BUi8uU/uEdWrc0c4s9oT43quYs7gHuvQm1z4cIclhJubBik69v12IoS6FjL9Qv/vX3v9y5gNgHHHY4JzSr+ay0R1VmhFwNrdL09AzOpLOZZHg/xAyjzR58Fz79zV75TDJBUi+V/pfUYLzg1x0F5vyQqNJcQh7UhYBmEOejCst3nWUsvjVHrwfCJio817N+6EgAQ=="
    }

    @Test
    fun `a remote keeps the button socket open for several presses`() {
        val main = friendlyTv(knownKey = "KEY-123")
        val buttons = FakeTv { emptyList() }
        val result = LgTv(connector(main, buttons), settleMs = 0).openRemote("192.168.1.86", "KEY-123")
        val remote = (result as LgTv.RemoteResult.Ready).remote
        assertEquals("KEY-123", remote.clientKey)
        assertFalse("nothing is closed while the menu is being steered", main.closed || buttons.closed)
        remote.press("DOWN")
        remote.press("DOWN")
        remote.press("ENTER")
        assertEquals(
            listOf("type:button\nname:DOWN\n\n", "type:button\nname:DOWN\n\n", "type:button\nname:ENTER\n\n"),
            buttons.sent,
        )
        remote.close()
        assertTrue(buttons.closed && main.closed)
    }

    @Test
    fun `a remote that was declined closes what it opened and says why`() {
        val tv = FakeTv { m ->
            when (m.optString("type")) {
                "hello" -> listOf(hello)
                "request" -> listOf(sysInfo)
                "register" -> listOf("""{"type":"error","id":"register_0","error":"403 User denied access"}""")
                else -> emptyList()
            }
        }
        val result = LgTv(connector(tv), settleMs = 0).openRemote("h", null)
        assertEquals(LgTv.Outcome.Declined, (result as LgTv.RemoteResult.Stopped).outcome)
        assertTrue(tv.closed)
    }

    @Test
    fun `a TV that will not give a button socket leaves nothing open`() {
        val tv = FakeTv { m ->
            when (m.optString("type")) {
                "hello" -> listOf(hello)
                "request" -> if (m.optString("id") == "sock") listOf("""{"type":"error","id":"sock","error":"no"}""") else listOf(sysInfo)
                "register" -> listOf(registered("K"))
                else -> emptyList()
            }
        }
        val result = LgTv(connector(tv), settleMs = 0).openRemote("h", "K")
        assertTrue((result as LgTv.RemoteResult.Stopped).outcome is LgTv.Outcome.Failed)
        assertTrue(tv.closed)
    }
}
