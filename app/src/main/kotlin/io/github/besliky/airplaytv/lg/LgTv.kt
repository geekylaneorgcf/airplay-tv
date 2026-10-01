package io.github.besliky.airplaytv.lg

import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Presses a button on an LG webOS TV over the network, the way LG's phone remote does: connect to the TV's
 * remote-control socket, register once (the TV shows a prompt that the owner accepts with the TV's own
 * remote, and answers with a key that is kept), then ask for a second socket that takes remote-control
 * buttons. `MENU` is the gear button, which opens the quick settings (picture mode, sound output, ...).
 *
 * The protocol is the one LG's own app and open-source clients such as aiowebostv (Apache-2.0) use.
 */
class LgTv(private val connector: Connector, private val settleMs: Long = 200) {

    interface Connection : Closeable {
        fun send(text: String)

        /** The next message; throws [SocketTimeoutException] when none comes within [timeoutMs]. */
        fun receive(timeoutMs: Int): String
    }

    fun interface Connector {
        fun open(host: String, port: Int, secure: Boolean, path: String): Connection
    }

    sealed class Outcome {
        /** The button was pressed (or, with no button, the pairing worked); [clientKey] is what to keep for next time. */
        data class Done(val clientKey: String) : Outcome()

        /** The TV's certificate is not trusted; see [TvTrust.Untrusted]. */
        data class Untrusted(val error: TvTrust.Untrusted) : Outcome()

        /** The prompt on the TV was declined. */
        object Declined : Outcome()

        /** The prompt on the TV was not answered in time. */
        object NoAnswer : Outcome()

        data class Unreachable(val reason: String) : Outcome()

        data class Failed(val reason: String) : Outcome()
    }

    /**
     * Presses [button] on the TV at [host], pairing first when [clientKey] is empty or no longer valid
     * ([onPrompt] is called when the TV asks its owner to accept). With a null [button] it only pairs.
     * Blocking: run it off the main thread.
     */
    fun press(host: String, clientKey: String?, button: String?, onPrompt: () -> Unit = {}): Outcome {
        val tv = try {
            openMain(host)
        } catch (e: TvTrust.Untrusted) {
            return Outcome.Untrusted(e)
        } catch (e: IOException) {
            return Outcome.Unreachable(e.message ?: "no connection")
        }
        tv.use {
            return try {
                talk(tv, clientKey, button, onPrompt)
            } catch (e: SocketTimeoutException) {
                Outcome.Failed("the TV did not answer")
            } catch (e: TvTrust.Untrusted) {
                Outcome.Untrusted(e)
            } catch (e: IOException) {
                Outcome.Failed(e.message ?: "the connection failed")
            } catch (e: JSONException) {
                Outcome.Failed("the TV sent something unexpected")
            }
        }
    }

    /** Over TLS first, which is what current TVs insist on; plain only when the TV does not offer TLS at all. */
    private fun openMain(host: String): Connection {
        try {
            return connector.open(host, WSS_PORT, true, "/")
        } catch (e: TvTrust.Untrusted) {
            throw e // never fall back to an unencrypted connection because of a certificate that was not accepted
        } catch (e: IOException) {
            return try {
                connector.open(host, WS_PORT, false, "/")
            } catch (_: IOException) {
                throw e
            }
        }
    }

    private fun talk(tv: Connection, clientKey: String?, button: String?, onPrompt: () -> Unit): Outcome {
        val key = when (val handshake = handshake(tv, clientKey, onPrompt)) {
            is Handshake.Registered -> handshake.key
            is Handshake.Stopped -> return handshake.outcome
        }
        if (button == null) return Outcome.Done(key)
        buttonSocket(tv).use { input ->
            input.send(buttonMessage(button))
            // the TV acts on a button when it arrives; closing at once can drop it
            if (settleMs > 0) Thread.sleep(settleMs)
        }
        return Outcome.Done(key)
    }

    private sealed class Handshake {
        class Registered(val key: String) : Handshake()
        class Stopped(val outcome: Outcome) : Handshake()
    }

    /** Hello, system info and registration: what has to happen before the TV takes any request. */
    private fun handshake(tv: Connection, clientKey: String?, onPrompt: () -> Unit): Handshake {
        tv.send(HELLO)
        readUntil(tv, REPLY_TIMEOUT_MS) { it.optString("type") == "hello" }
        // newer webOS wants the system info before it will register a client
        tv.send(SYSTEM_INFO)
        readUntil(tv, REPLY_TIMEOUT_MS) { it.optString("id") == "get_sys_info" }

        tv.send(registerMessage(clientKey))
        return when (val registration = awaitRegistration(tv, onPrompt)) {
            is Registration.Key -> Handshake.Registered(registration.value)
            Registration.Declined -> Handshake.Stopped(Outcome.Declined)
            Registration.NoAnswer -> Handshake.Stopped(Outcome.NoAnswer)
        }
    }

    /** Asks the registered TV for its button socket and opens it. Throws [IOException] when the TV will not give one. */
    private fun buttonSocket(tv: Connection): Connection {
        tv.send(request("sock", "ssap://com.webos.service.networkinput/getPointerInputSocket"))
        val reply = readUntil(tv, REPLY_TIMEOUT_MS) { it.optString("id") == "sock" }
        val payload = reply.optJSONObject("payload")
        if (reply.optString("type") == "error" || payload == null || !payload.optBoolean("returnValue", false)) {
            throw IOException("the TV would not open its button socket: ${reply.optString("error")}")
        }
        val target = parseSocketPath(payload.getString("socketPath"), fallbackHost = null)
            ?: throw IOException("the TV's button socket address was not understood")
        return connector.open(target.host, target.port, target.secure, target.path)
    }

    /** A button socket that stays open for several presses, as when someone steers the TV's menu with the arrow keys. */
    class Remote internal constructor(private val main: Connection, private val buttons: Connection, val clientKey: String) : Closeable {
        /** Presses [button] (`UP`, `DOWN`, `LEFT`, `RIGHT`, `ENTER`, `BACK`, `EXIT`, `MENU`, ...). Throws [IOException] when the TV is gone. */
        fun press(button: String) = buttons.send(buttonMessage(button))

        override fun close() {
            try {
                buttons.close()
            } finally {
                main.close()
            }
        }
    }

    sealed class RemoteResult {
        class Ready(val remote: Remote) : RemoteResult()
        class Stopped(val outcome: Outcome) : RemoteResult()
    }

    /**
     * Connects and registers like [press] but keeps the button socket open and hands it over. The caller closes it.
     * Blocking: run it off the main thread.
     */
    fun openRemote(host: String, clientKey: String?, onPrompt: () -> Unit = {}): RemoteResult {
        val main = try {
            openMain(host)
        } catch (e: TvTrust.Untrusted) {
            return RemoteResult.Stopped(Outcome.Untrusted(e))
        } catch (e: IOException) {
            return RemoteResult.Stopped(Outcome.Unreachable(e.message ?: "no connection"))
        }
        try {
            return when (val handshake = handshake(main, clientKey, onPrompt)) {
                is Handshake.Stopped -> {
                    main.close()
                    RemoteResult.Stopped(handshake.outcome)
                }
                is Handshake.Registered -> RemoteResult.Ready(Remote(main, buttonSocket(main), handshake.key))
            }
        } catch (e: Exception) {
            try {
                main.close()
            } catch (_: IOException) {
                // already gone
            }
            return RemoteResult.Stopped(
                when (e) {
                    is TvTrust.Untrusted -> Outcome.Untrusted(e)
                    is SocketTimeoutException -> Outcome.Failed("the TV did not answer")
                    is IOException -> Outcome.Failed(e.message ?: "the connection failed")
                    is JSONException -> Outcome.Failed("the TV sent something unexpected")
                    else -> throw e
                },
            )
        }
    }

    private sealed class Registration {
        class Key(val value: String) : Registration()
        object Declined : Registration()
        object NoAnswer : Registration()
    }

    private fun awaitRegistration(tv: Connection, onPrompt: () -> Unit): Registration {
        val deadline = System.nanoTime() + PAIRING_TIMEOUT_MS * 1_000_000L
        var prompted = false
        while (true) {
            val leftMs = (deadline - System.nanoTime()) / 1_000_000L
            if (leftMs <= 0) return Registration.NoAnswer
            val message = try {
                JSONObject(tv.receive(leftMs.toInt()))
            } catch (_: SocketTimeoutException) {
                return Registration.NoAnswer
            }
            when (message.optString("type")) {
                "registered" -> return Registration.Key(message.getJSONObject("payload").getString("client-key"))
                "error" -> return Registration.Declined
                "response" -> {
                    val pairing = message.optJSONObject("payload")?.optString("pairingType")
                    if (!prompted && pairing == "PROMPT") {
                        prompted = true
                        onPrompt()
                    }
                }
            }
        }
    }

    private fun readUntil(tv: Connection, timeoutMs: Int, wanted: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val leftMs = (deadline - System.nanoTime()) / 1_000_000L
            if (leftMs <= 0) throw SocketTimeoutException("no answer from the TV")
            val message = JSONObject(tv.receive(leftMs.toInt()))
            if (wanted(message)) return message
        }
    }

    internal class Target(val host: String, val port: Int, val secure: Boolean, val path: String)

    companion object {
        const val WS_PORT = 3000
        const val WSS_PORT = 3001
        const val MENU = "MENU"

        private const val REPLY_TIMEOUT_MS = 8_000
        private const val PAIRING_TIMEOUT_MS = 60_000L

        private const val HELLO = """{"id":"hello","type":"hello","payload":{}}"""
        private const val SYSTEM_INFO = """{"id":"get_sys_info","type":"request","uri":"ssap://system/getSystemInfo","payload":{}}"""

        /** What a client may do once the owner has accepted it; the set LG's own tools and the open-source clients ask for. */
        private val PERMISSIONS = listOf(
            "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT", "CONTROL_INPUT_TV",
            "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN", "LAUNCH", "LAUNCH_WEBAPP",
            "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
            "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS", "READ_NETWORK_STATE", "READ_NOTIFICATIONS",
            "READ_POWER_STATE", "READ_RUNNING_APPS", "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME",
            "READ_UPDATE_INFO", "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP",
            "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS",
        )

        internal fun registerMessage(clientKey: String?): String {
            val payload = JSONObject()
                .put("forcePairing", false)
                .put("pairingType", "PROMPT")
                .put(
                    "manifest",
                    JSONObject().put("manifestVersion", 1).put("appVersion", "1.1").put("permissions", JSONArray(PERMISSIONS)),
                )
            if (!clientKey.isNullOrEmpty()) payload.put("client-key", clientKey)
            return JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString()
        }

        internal fun buttonMessage(button: String): String = "type:button\nname:$button\n\n"

        internal fun request(id: String, uri: String): String =
            JSONObject().put("id", id).put("type", "request").put("uri", uri).put("payload", JSONObject()).toString()

        /**
         * Reads the address of the TV's button socket (`wss://host:3001/resources/.../netinput.pointer.sock`).
         * [fallbackHost] stands in when the TV does not name one.
         */
        internal fun parseSocketPath(socketPath: String, fallbackHost: String?): Target? {
            val uri = try {
                URI(socketPath)
            } catch (_: Exception) {
                return null
            }
            val secure = when (uri.scheme?.lowercase()) {
                "wss" -> true
                "ws" -> false
                else -> return null
            }
            val host = uri.host ?: fallbackHost ?: return null
            val port = if (uri.port > 0) uri.port else if (secure) WSS_PORT else WS_PORT
            val path = (uri.rawPath ?: "/").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            return Target(host, port, secure, path)
        }
    }
}
