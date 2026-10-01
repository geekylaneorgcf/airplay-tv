package io.github.besliky.airplaytv.service

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * A second source for lyrics, used only when LRCLIB has none and the owner has switched it on: the lyrics YouTube
 * Music shows for a song (plain text, not time-stamped). They are asked for the way the YouTube Music website asks
 * (search, then the song's page, then its lyrics tab), which is not a documented interface and may change without
 * notice; when it does the lookup simply finds nothing. Only the title and artist are sent.
 */
internal object YtmLyrics {

    /** A song of a search answer. */
    class Song(val videoId: String, val title: String, val byline: String)

    /** What a song's page says: where its lyrics are (null when the song has none) and its length in seconds (-1 when unknown). */
    class Page(val lyricsBrowseId: String?, val lengthS: Int)

    private const val BASE = "https://music.youtube.com/youtubei/v1/"
    private const val SONGS_ONLY = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val MAX_SONGS_TRIED = 2
    private const val MAX_BYTES = 3_000_000

    /** Plain lyrics are the same words in any cut of a song, so the length may differ a lot, but not by another song's worth. */
    internal const val MAX_DIFF_S = 45.0

    private fun context(): JSONObject = JSONObject().put(
        "client",
        JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20240612.01.00").put("hl", "en").put("gl", "US"),
    )

    /**
     * Looks the song up and returns its plain lyrics, or null. [post] sends a request body to an endpoint and returns
     * the answer, or null when there was none; it is a parameter so the lookup can be tested without a network.
     */
    fun find(title: String, artist: String, durationMs: Long, post: (String, JSONObject) -> String?): Lyrics? {
        val query = listOf(LyricsRepository.cleanTitle(title), LyricsRepository.artistsOf(artist).firstOrNull() ?: artist.trim())
            .filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        val search = post("search", JSONObject().put("context", context()).put("query", query).put("params", SONGS_ONLY)) ?: return null
        var tried = 0
        for (song in parseSearch(search)) {
            if (!matches(song, title, artist)) continue
            if (++tried > MAX_SONGS_TRIED) break
            val next = post("next", JSONObject().put("context", context()).put("videoId", song.videoId).put("isAudioOnly", true)) ?: continue
            val page = parseNext(next, song.videoId)
            val browseId = page.lyricsBrowseId ?: continue
            if (durationMs > 0 && page.lengthS > 0 && abs(page.lengthS - durationMs / 1000.0) > MAX_DIFF_S) continue
            val browse = post("browse", JSONObject().put("context", context()).put("browseId", browseId)) ?: continue
            val text = parseLyrics(browse) ?: continue
            val lines = Lyrics.parsePlain(text)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = false)
        }
        return null
    }

    /** Sends [body] to the YouTube Music [endpoint]; null unless it answered 200. */
    fun post(endpoint: String, body: JSONObject, version: String): String? {
        val connection = URL("$BASE$endpoint?prettyPrint=false").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 6000
            connection.readTimeout = 9000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "AirPlayTV-fork/$version (https://github.com/geekylaneorgcf/airplay-tv)")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null else connection.inputStream.use { LyricsRepository.readLimited(it, MAX_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    /** Whether [song] is the one asked for: the same title once decoration is stripped, and one of the artists in its byline. */
    internal fun matches(song: Song, title: String, artist: String): Boolean {
        val wanted = simple(LyricsRepository.cleanTitle(title))
        if (wanted.isEmpty() || simple(LyricsRepository.cleanTitle(song.title)) != wanted) return false
        val artists = LyricsRepository.artistsOf(artist).map(::simple).filter { it.isNotEmpty() }
        val byline = simple(song.byline)
        return artists.isEmpty() || artists.any { it in byline }
    }

    private fun simple(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- reading the answers

    internal fun parseSearch(json: String): List<Song> {
        val root = parse(json) ?: return emptyList()
        return collect(root, "musicResponsiveListItemRenderer").mapNotNull { (it as? JSONObject)?.let(::song) }
    }

    private fun song(item: JSONObject): Song? {
        val videoId = item.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (videoId.isEmpty()) return null
        val columns = item.optJSONArray("flexColumns") ?: return null
        fun column(i: Int): String = columns.optJSONObject(i)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
            ?.let(::joinRuns).orEmpty()
        val title = column(0)
        return if (title.isEmpty()) null else Song(videoId, title, column(1))
    }

    internal fun parseNext(json: String, videoId: String): Page {
        val root = parse(json) ?: return Page(null, -1)
        var browseId: String? = null
        for (group in collect(root, "tabs")) {
            val tabs = group as? JSONArray ?: continue
            for (i in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer") ?: continue
                if (tab.optString("title") == "Lyrics" && !tab.optBoolean("unselectable", false)) {
                    browseId = tab.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.ifEmpty { null }
                }
            }
        }
        var length = -1
        for (panel in collect(root, "playlistPanelVideoRenderer")) {
            val video = panel as? JSONObject ?: continue
            if (video.optString("videoId") == videoId) {
                length = seconds(video.optJSONObject("lengthText")?.optJSONArray("runs")?.let(::joinRuns))
                break
            }
        }
        return Page(browseId, length)
    }

    internal fun parseLyrics(json: String): String? {
        val root = parse(json) ?: return null
        val shelf = collect(root, "musicDescriptionShelfRenderer").firstOrNull() as? JSONObject ?: return null
        val text = shelf.optJSONObject("description")?.optJSONArray("runs")?.let(::joinRuns).orEmpty().trim()
        return text.ifEmpty { null }
    }

    /** "2:36" as 156; "1:02:03" as 3723; -1 for anything else. */
    internal fun seconds(text: String?): Int {
        val parts = text?.trim()?.split(':') ?: return -1
        if (parts.size !in 2..3) return -1
        var total = 0
        for (part in parts) total = total * 60 + (part.toIntOrNull() ?: return -1)
        return total
    }

    private fun parse(json: String): JSONObject? = try {
        JSONObject(json)
    } catch (_: Exception) {
        null
    }

    private fun joinRuns(runs: JSONArray): String = (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }

    /** Every value stored under [key], anywhere in [json]; the answers nest the same pieces at different depths. */
    private fun collect(json: Any?, key: String): List<Any> {
        val out = ArrayList<Any>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val value = node.opt(k)
                        if (k == key && value != null) out += value
                        walk(value)
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(json)
        return out
    }
}
