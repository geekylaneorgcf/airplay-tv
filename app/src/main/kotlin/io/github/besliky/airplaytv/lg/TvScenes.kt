package io.github.besliky.airplaytv.lg

/**
 * A scene is a few things set on the TV in one go: the input, the picture mode, the sound output, a volume that is not exceeded and
 * whether the screen goes off (for music). An empty field leaves that thing as it is. Pure, so it is tested without a TV.
 */
object TvScenes {

    val IDS = listOf("movie", "game", "music", "night")

    /** [input] is "" (leave), "ps5" (the input the TV names like a PlayStation) or `HDMI_1` to `HDMI_4`; [volume] is at most this, -1 leaves it. */
    data class Scene(val id: String, val input: String, val picture: String, val sound: String, val volume: Int, val screenOff: Boolean)

    fun label(id: String): String = when (id) {
        "movie" -> "Movie"
        "game" -> "Game"
        "music" -> "Music"
        "night" -> "Night"
        else -> id
    }

    fun default(id: String): Scene = when (id) {
        "movie" -> Scene(id, "", "cinema", "", -1, false)
        "game" -> Scene(id, "ps5", "game", "", -1, false)
        "music" -> Scene(id, "", "", "", -1, true)
        "night" -> Scene(id, "", "cinema", "", 8, false)
        else -> Scene(id, "", "", "", -1, false)
    }

    fun encode(scene: Scene): String =
        "${scene.input}|${scene.picture}|${scene.sound}|${scene.volume}|${if (scene.screenOff) 1 else 0}"

    /** The scene [id] as it was stored, or its default when nothing (or nothing readable) is stored. */
    fun parse(id: String, stored: String?): Scene {
        val parts = stored?.split('|') ?: return default(id)
        if (parts.size != 5) return default(id)
        val volume = parts[3].toIntOrNull() ?: return default(id)
        if (volume < -1 || volume > 100) return default(id)
        return Scene(id, parts[0], parts[1], parts[2], volume, parts[4] == "1")
    }

    private val PLAYSTATION = Regex("ps5|ps4|playstation", RegexOption.IGNORE_CASE)

    /** The inputs a scene may name, in the order the settings page cycles through them. */
    val INPUT_CHOICES = listOf("", "ps5", "HDMI_1", "HDMI_2", "HDMI_3", "HDMI_4")

    fun inputLabel(token: String): String = when (token) {
        "" -> "Leave"
        "ps5" -> "PS5 (By Name)"
        else -> token.replace('_', ' ')
    }

    /** The TV's input id (`HDMI_2`) a scene's input stands for, or null when it leaves the input or no input has a PlayStation's name. */
    fun resolveInput(token: String, inputs: List<LgFacts.Input>): String? {
        if (token.isEmpty()) return null
        if (token != "ps5") return token
        val named = inputs.filter { PLAYSTATION.containsMatchIn(it.label) }
        val pick = named.firstOrNull { it.connected } ?: named.firstOrNull()
        return LgFacts.inputIdOf(pick?.appId)
    }

    /** The volume to set for a scene that caps it at [wanted]: nothing when there is no cap or the TV is at or below it already (a scene never raises the sound). */
    fun cappedVolume(current: Int?, wanted: Int): Int? = if (wanted < 0 || current == null || current <= wanted) null else wanted

    val VOLUME_CHOICES = listOf(-1, 5, 8, 10, 12, 15, 20, 25, 30)
}
