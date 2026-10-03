package io.github.besliky.airplaytv.lg

import org.json.JSONArray
import org.json.JSONObject

/**
 * What an LG webOS TV calls its picture modes and sound outputs, and the requests that read and change them. Pure, so it is tested
 * without a TV. The names are the ones the TV's own settings service uses (the open-source clients aiowebostv and bscpylgtv know them too).
 */
object TvPicture {

    data class Mode(val id: String, val label: String)

    /** The picture modes offered, in the order the panel cycles through them. A TV that is in another mode (HDR, Eco, ...) shows that one by its id. */
    val MODES = listOf(
        Mode("standard", "Standard"),
        Mode("cinema", "Cinema"),
        Mode("game", "Game"),
        Mode("filmMaker", "Filmmaker"),
        Mode("vivid", "Vivid"),
        Mode("sports", "Sports"),
        Mode("expert1", "Expert (Bright Room)"),
        Mode("expert2", "Expert (Dark Room)"),
    )

    fun label(id: String?): String =
        if (id.isNullOrEmpty()) "Unknown" else MODES.firstOrNull { it.id.equals(id, ignoreCase = true) }?.label ?: id

    /** The picture mode in an answer of `ssap://settings/getSystemSettings`, or null. */
    fun read(message: JSONObject): String? =
        message.optJSONObject("payload")?.optJSONObject("settings")?.optString("pictureMode")?.takeIf { it.isNotEmpty() && it != "null" }

    fun readRequest(): JSONObject = JSONObject().put("category", "picture").put("keys", JSONArray().put("pictureMode"))

    fun writeRequest(id: String): JSONObject =
        JSONObject().put("category", "picture").put("settings", JSONObject().put("pictureMode", id))
}

object TvSound {

    data class Output(val id: String, val label: String)

    val OUTPUTS = listOf(
        Output("tv_speaker", "TV Speakers"),
        Output("external_arc", "HDMI ARC"),
        Output("external_optical", "Optical"),
        Output("bt_soundbar", "Bluetooth Soundbar"),
        Output("lineout", "Line Out"),
        Output("headphone", "Headphones"),
    )

    fun label(id: String?): String =
        if (id.isNullOrEmpty()) "Unknown" else OUTPUTS.firstOrNull { it.id.equals(id, ignoreCase = true) }?.label ?: id

    /** The sound output in an answer of `ssap://com.webos.service.apiadapter/audio/getSoundOutput`, or null. */
    fun read(message: JSONObject): String? =
        message.optJSONObject("payload")?.optString("soundOutput")?.takeIf { it.isNotEmpty() && it != "null" }

    fun writeRequest(id: String): JSONObject = JSONObject().put("output", id)
}

/**
 * Newer webOS firmware does not take `ssap://settings/setSystemSettings` for the picture settings. The settings service is reached
 * through the alert API instead, as LG's own tools and the open-source clients do: an alert whose button calls the settings service, closed
 * at once so that the button is pressed for nobody and the call is made.
 */
object TvLuna {

    const val SET_SYSTEM_SETTINGS = "luna://com.webos.settingsservice/setSystemSettings"

    fun alertRequest(lunaUri: String, params: JSONObject): JSONObject = JSONObject()
        .put("message", " ")
        .put("buttons", JSONArray().put(JSONObject().put("label", "").put("onClick", lunaUri).put("params", params)))
        .put("onclose", JSONObject().put("uri", lunaUri).put("params", params))
        .put("onfail", JSONObject().put("uri", lunaUri).put("params", params))

    /** The id of the alert in an answer of `createAlert`, or null. */
    fun alertId(message: JSONObject?): String? =
        message?.optJSONObject("payload")?.optString("alertId")?.takeIf { it.isNotEmpty() && it != "null" }
}

/** The choice after [current] in [choices] (wrapping round), [step] places on: -1 goes back. A value that is not in the list starts at the first (or last, going back). */
fun <T> cycle(choices: List<T>, current: T?, step: Int): T {
    require(choices.isNotEmpty())
    val at = choices.indexOf(current)
    if (at < 0) return if (step >= 0) choices.first() else choices.last()
    return choices[Math.floorMod(at + step, choices.size)]
}
