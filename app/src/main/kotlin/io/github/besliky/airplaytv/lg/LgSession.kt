package io.github.besliky.airplaytv.lg

import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONException
import org.json.JSONObject

/**
 * A registered connection to an LG TV that stays open: requests that are answered (`ssap://audio/setVolume`, ...) and
 * subscriptions that keep telling (which input is in front, whether the screen is on). It is what [LgTv.openSession] hands out
 * once the TV has accepted the client; a thread reads what the TV says and hands each message to whoever asked for it.
 */
class LgSession internal constructor(private val main: LgTv.Connection, val clientKey: String) : Closeable {

    private val answers = ConcurrentHashMap<String, LinkedBlockingQueue<JSONObject>>()
    private val subscriptions = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val ids = AtomicInteger()
    private val sendLock = Any()
    private val reader = Thread({ readLoop() }, "lg-session").apply { isDaemon = true }

    @Volatile
    var closed = false
        private set

    /** Called once, from the reading thread, when the connection ends (the TV went off, or the session was closed). */
    @Volatile
    var onClosed: (() -> Unit)? = null

    internal fun start() = reader.start()

    private fun readLoop() {
        try {
            while (!closed) {
                val before = System.nanoTime()
                val text = try {
                    main.receive(READ_TIMEOUT_MS)
                } catch (_: SocketTimeoutException) {
                    // a connection that gives up at once would make this loop spin
                    if (System.nanoTime() - before < 5_000_000L) Thread.sleep(5)
                    continue
                }
                val message = try {
                    JSONObject(text)
                } catch (_: JSONException) {
                    continue
                }
                val id = message.optString("id")
                subscriptions[id]?.invoke(message)
                answers[id]?.offer(message)
            }
        } catch (_: IOException) {
            // the TV closed the connection
        } catch (_: InterruptedException) {
            // closed
        } finally {
            closed = true
            onClosed?.invoke()
        }
    }

    private fun write(message: JSONObject) {
        synchronized(sendLock) { main.send(message.toString()) }
    }

    /**
     * Asks [uri] and waits for the answer, which is returned as the TV sent it (`payload.returnValue` says whether it worked);
     * null when the TV did not answer within [timeoutMs] or the connection is gone. Blocking: not for the main thread.
     */
    fun request(uri: String, payload: JSONObject = JSONObject(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject? {
        if (closed) return null
        val id = "q${ids.incrementAndGet()}"
        val queue = LinkedBlockingQueue<JSONObject>()
        answers[id] = queue
        try {
            write(JSONObject().put("id", id).put("type", "request").put("uri", uri).put("payload", payload))
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: IOException) {
            return null
        } finally {
            answers.remove(id)
        }
    }

    /** Asks [uri] to keep telling: [onMessage] gets every message the TV sends for it, on the reading thread. False when it could not be asked. */
    fun subscribe(uri: String, payload: JSONObject = JSONObject(), onMessage: (JSONObject) -> Unit): Boolean {
        if (closed) return false
        val id = "s${ids.incrementAndGet()}"
        subscriptions[id] = onMessage
        return try {
            write(JSONObject().put("id", id).put("type", "subscribe").put("uri", uri).put("payload", payload))
            true
        } catch (_: IOException) {
            subscriptions.remove(id)
            false
        }
    }

    override fun close() {
        closed = true
        try {
            main.close()
        } catch (_: IOException) {
            // gone already
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 20_000
        private const val DEFAULT_TIMEOUT_MS = 4_000L

        /** Whether an answer says the request worked. */
        fun succeeded(answer: JSONObject?): Boolean =
            answer != null && answer.optString("type") != "error" && answer.optJSONObject("payload")?.optBoolean("returnValue", true) != false
    }
}

/** What the TV reports about itself, as the app needs it. Pure parsing, so it can be tested without a TV. */
object LgFacts {

    /** `ssap://com.webos.applicationManager/getForegroundAppInfo` answers with the id of the app in front: `com.webos.app.hdmi1` for an HDMI input. */
    fun foregroundApp(message: JSONObject): String? =
        message.optJSONObject("payload")?.optString("appId")?.takeIf { it.isNotEmpty() }

    /** The input id the TV's `switchInput` takes for the foreground app of an HDMI input (`com.webos.app.hdmi2` is `HDMI_2`), or null for anything else. */
    fun inputIdOf(appId: String?): String? {
        val match = appId?.let { Regex("""com\.webos\.app\.hdmi(\d)""").matchEntire(it) } ?: return null
        return "HDMI_${match.groupValues[1]}"
    }

    /** The state in a power-state message (`Active`, `Active Standby`, `Screen Off`, `Suspend`, ...), or null when it names none. */
    fun powerState(message: JSONObject): String? =
        message.optJSONObject("payload")?.optString("state")?.takeIf { it.isNotEmpty() }

    /** Whether the TV shows a picture in [state]. A TV that names no state is taken to. */
    fun showsPicture(state: String?): Boolean = state == null || state.equals("Active", ignoreCase = true)

    /** What `ssap://audio/getVolume` says: the volume, and whether the TV lets it be changed (a TV sending its sound out to a fixed output does not). */
    data class Volume(val level: Int, val muted: Boolean, val adjustable: Boolean)

    fun volume(message: JSONObject): Volume? {
        val payload = message.optJSONObject("payload") ?: return null
        val status = payload.optJSONObject("volumeStatus") ?: payload
        if (!status.has("volume")) return null
        // older TVs send the volume and the mute flag at the top; newer ones say whether it can be adjusted
        return Volume(
            status.optInt("volume", 0),
            status.optBoolean("muteStatus", payload.optBoolean("muted", false)),
            status.optBoolean("adjustVolume", true),
        )
    }

    /** One entry of `ssap://tv/getExternalInputList`: [appId] is the app of the input (`com.webos.app.hdmi1`), [label] the name the TV shows for it. */
    data class Input(val appId: String, val label: String, val connected: Boolean)

    /** The HDMI inputs in an answer of `ssap://tv/getExternalInputList`. */
    fun inputs(message: JSONObject): List<Input> {
        val devices = message.optJSONObject("payload")?.optJSONArray("devices") ?: return emptyList()
        val found = ArrayList<Input>()
        for (i in 0 until devices.length()) {
            val device = devices.optJSONObject(i) ?: continue
            val appId = device.optString("appId")
            if (inputIdOf(appId) == null) continue
            found.add(Input(appId, device.optString("label"), device.optBoolean("connected", false)))
        }
        return found
    }

    /**
     * The app of the one connected input that the TV names like an Amazon stick ("Fire TV", "Amazon Fire TV Stick": an input is named
     * after the device that reports its name), or null when no input or more than one is.
     */
    fun stickInput(inputs: List<Input>): String? =
        inputs.filter { it.connected && STICK_NAME.containsMatchIn(it.label) }.map { it.appId }.distinct().singleOrNull()

    private val STICK_NAME = Regex("fire|amazon", RegexOption.IGNORE_CASE)

    /** The hardware addresses in an answer of `ssap://com.webos.service.connectionmanager/getinfo` (the wired and the wireless one). */
    fun macAddresses(message: JSONObject): List<String> {
        val payload = message.optJSONObject("payload") ?: return emptyList()
        return listOf("wiredInfo", "wifiInfo")
            .mapNotNull { payload.optJSONObject(it)?.optString("macAddress") }
            .mapNotNull { text -> Wol.parseMac(text)?.let(Wol::format) }
            .distinct()
    }
}
