package io.github.besliky.airplaytv.lg

import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SERVICE
import io.github.besliky.airplaytv.Settings
import java.io.Closeable
import org.json.JSONObject

/**
 * Reads and changes what an LG TV is doing, over a registered connection ([LgSession]): the input in front, the picture mode, the sound
 * output, the volume, the power. Every change is checked by reading it back, because a TV that refuses a setting often says nothing.
 * Blocking: not for the main thread. It is what the quick panel, the scenes and the sleep timer speak through; they never talk to the TV
 * in any other way.
 */
class TvControl(private val session: LgSession) : Closeable {

    /** Everything the panel shows. A field the TV did not answer is null (or empty). */
    class State(
        val appId: String?,
        val inputs: List<LgFacts.Input>,
        val picture: String?,
        val sound: String?,
        val volume: LgFacts.Volume?,
        val power: String?,
    ) {
        /** The input in front as `HDMI_2`, or null when the TV shows something else (its own apps, a channel). */
        val inputId: String? get() = LgFacts.inputIdOf(appId)

        /** The connected inputs, as the panel offers them. */
        val connectedInputs: List<LgFacts.Input> get() = inputs.filter { it.connected }
    }

    val closed: Boolean get() = session.closed

    fun read(): State {
        val app = session.request("ssap://com.webos.applicationManager/getForegroundAppInfo", timeoutMs = READ_MS)?.let { LgFacts.foregroundApp(it) }
        val inputs = session.request("ssap://tv/getExternalInputList", timeoutMs = READ_MS)?.let { LgFacts.inputs(it) }.orEmpty()
        return State(app, inputs, readPicture(), readSound(), readVolume(), readPower())
    }

    fun readPicture(): String? =
        session.request("ssap://settings/getSystemSettings", TvPicture.readRequest(), READ_MS)?.let { TvPicture.read(it) }

    fun readSound(): String? =
        session.request("ssap://com.webos.service.apiadapter/audio/getSoundOutput", timeoutMs = READ_MS)?.let { TvSound.read(it) }

    fun readVolume(): LgFacts.Volume? = session.request("ssap://audio/getVolume", timeoutMs = READ_MS)?.let { LgFacts.volume(it) }

    fun readPower(): String? =
        session.request("ssap://com.webos.service.tvpower/power/getPowerState", timeoutMs = READ_MS)?.let { LgFacts.powerState(it) }

    /**
     * Sets the picture mode and checks that it took. The direct request first; a TV that does not take it (newer firmware) gets it through
     * the settings service by the alert workaround, and is remembered to.
     */
    fun setPicture(id: String): Boolean {
        if (!pictureViaAlert) {
            val answer = session.request("ssap://settings/setSystemSettings", TvPicture.writeRequest(id), WRITE_MS)
            if (LgSession.succeeded(answer) && pictureIs(id)) return true
        }
        val ok = luna(TvLuna.SET_SYSTEM_SETTINGS, TvPicture.writeRequest(id)) && pictureIs(id)
        if (ok) pictureViaAlert = true
        Log.i(SERVICE, "LG picture mode $id: ${if (ok) "done (through the settings service)" else "the TV did not take it"}")
        return ok
    }

    fun setSound(id: String): Boolean {
        val answer = session.request("ssap://com.webos.service.apiadapter/audio/changeSoundOutput", TvSound.writeRequest(id), WRITE_MS)
        if (!LgSession.succeeded(answer)) {
            Log.i(SERVICE, "LG sound output $id: the TV did not take it")
            return false
        }
        Thread.sleep(SETTLE_MS)
        val now = readSound()
        val ok = now == null || now.equals(id, ignoreCase = true) // a TV that does not say its output back is believed
        Log.i(SERVICE, "LG sound output $id: ${if (ok) "done" else "the TV is on $now"}")
        return ok
    }

    fun setVolume(level: Int): Boolean =
        LgSession.succeeded(session.request("ssap://audio/setVolume", JSONObject().put("volume", level.coerceIn(0, 100)), WRITE_MS))

    fun switchInput(inputId: String): Boolean {
        val ok = LgSession.succeeded(session.request("ssap://tv/switchInput", JSONObject().put("inputId", inputId), WRITE_MS))
        Log.i(SERVICE, "LG switch to $inputId: ${if (ok) "done" else "no"}")
        return ok
    }

    /** Puts the TV on standby, as its own power button does. */
    fun turnOff(): Boolean = LgSession.succeeded(session.request("ssap://system/turnOff", timeoutMs = WRITE_MS))

    fun screenOff(): Boolean = LgSession.succeeded(session.request("ssap://com.webos.service.tvpower/power/turnOffScreen", timeoutMs = WRITE_MS))

    fun screenOn(): Boolean = LgSession.succeeded(session.request("ssap://com.webos.service.tvpower/power/turnOnScreen", timeoutMs = WRITE_MS))

    /** A message on the TV's screen, whatever input it shows (the TV's own toast). */
    fun toast(text: String): Boolean =
        LgSession.succeeded(session.request("ssap://system.notifications/createToast", JSONObject().put("message", text), WRITE_MS))

    /** Keeps telling about the input in front; see [LgSession.subscribe]. */
    fun onForegroundApp(onApp: (String?) -> Unit): Boolean =
        session.subscribe("ssap://com.webos.applicationManager/getForegroundAppInfo") { onApp(LgFacts.foregroundApp(it)) }

    var onClosed: (() -> Unit)?
        get() = session.onClosed
        set(value) {
            session.onClosed = value
        }

    private fun pictureIs(id: String): Boolean {
        Thread.sleep(SETTLE_MS)
        return readPicture().equals(id, ignoreCase = true)
    }

    /** A call to the TV's own settings service, through an alert that is closed as it opens. */
    private fun luna(uri: String, params: JSONObject): Boolean {
        val created = session.request("ssap://system.notifications/createAlert", TvLuna.alertRequest(uri, params), WRITE_MS) ?: return false
        val id = TvLuna.alertId(created) ?: return false
        session.request("ssap://system.notifications/closeAlert", JSONObject().put("alertId", id), WRITE_MS)
        return true
    }

    override fun close() = session.close()

    companion object {
        private const val READ_MS = 3_000L
        private const val WRITE_MS = 5_000L
        private const val SETTLE_MS = 450L

        /** Set once the direct picture request was seen not to work on this TV. */
        @Volatile
        private var pictureViaAlert = false

        /** A connection to the paired TV, or null when it is off, not paired, or did not answer within [timeoutMs]. Blocking. */
        fun open(settings: Settings, timeoutMs: Int = 2500): TvControl? {
            if (!TvSettings.paired(settings)) return null
            val result = LgTv(TvSettings.connector(settings, timeoutMs)).openSession(settings.lgHost, settings.lgClientKey.ifEmpty { null })
            return when (result) {
                is LgTv.SessionResult.Ready -> TvControl(result.session)
                is LgTv.SessionResult.Stopped -> {
                    Log.i(SERVICE, "LG the TV did not open a connection: ${result.outcome}")
                    null
                }
            }
        }
    }
}
