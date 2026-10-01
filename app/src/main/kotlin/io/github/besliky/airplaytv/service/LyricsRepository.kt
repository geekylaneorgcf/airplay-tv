package io.github.besliky.airplaytv.service

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Looks up lyrics on LRCLIB (https://lrclib.net), a free community lyrics database. Only the song
 * title and artist are sent. Candidates are ranked by whether they have time-stamped lines, how close
 * their length is to the track's, and an exact title/artist match. Results, including "nothing
 * found", are cached for the life of the process.
 *
 * This is the only place in the app that talks to the internet, and it is only used when the user
 * turns Lyrics on.
 */
object LyricsRepository {

    private const val TAG = "AirPlayTV-Lyrics"
    private const val SEARCH_URL = "https://lrclib.net/api/search"
    private const val MAX_RESPONSE_BYTES = 3_000_000
    private const val MAX_DURATION_DIFF_S = 10.0
    private const val CACHE_SIZE = 40

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lyrics").apply { isDaemon = true }
    }

    private val cache = object : LinkedHashMap<String, Lyrics?>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?): Boolean = size > CACHE_SIZE
    }

    /** Looks up lyrics. [onResult] runs on a background thread; null means nothing usable was found. */
    fun load(title: String, artist: String, durationMs: Long, version: String, onResult: (Lyrics?) -> Unit) {
        val key = "${norm(title)}|${norm(artist)}|${durationMs / 5000}"
        val cached: Lyrics?
        val hit: Boolean
        synchronized(cache) {
            hit = cache.containsKey(key)
            cached = cache[key]
        }
        executor.execute {
            if (hit) {
                onResult(cached)
                return@execute
            }
            val result = try {
                fetch(title, artist, durationMs, version)
            } catch (e: Exception) {
                Log.w(TAG, "lyrics lookup failed: ${e.javaClass.simpleName}")
                onResult(null) // not cached: a network hiccup should not hide the lyrics for good
                return@execute
            }
            synchronized(cache) { cache[key] = result }
            onResult(result)
        }
    }

    private fun fetch(title: String, artist: String, durationMs: Long, version: String): Lyrics? {
        val url = URL("$SEARCH_URL?track_name=${encode(title)}&artist_name=${encode(artist)}")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 9000
        connection.setRequestProperty("User-Agent", "AirPlayTV-fork/$version (https://github.com/geekylaneorgcf/airplay-tv)")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.use { readLimited(it) }
            return pick(JSONArray(body), title, artist, durationMs)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_RESPONSE_BYTES) throw IOException("response too large")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun pick(results: JSONArray, title: String, artist: String, durationMs: Long): Lyrics? {
        val wantSeconds = if (durationMs > 0) durationMs / 1000.0 else -1.0
        var best: JSONObject? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val synced = text(r, "syncedLyrics")
            val plain = text(r, "plainLyrics")
            val instrumental = r.optBoolean("instrumental", false)
            if (synced.isBlank() && plain.isBlank() && !instrumental) continue

            var score = when {
                synced.isNotBlank() -> 100.0
                plain.isNotBlank() -> 40.0
                else -> 10.0
            }
            val seconds = r.optDouble("duration", -1.0)
            if (wantSeconds > 0 && seconds > 0) {
                val diff = abs(seconds - wantSeconds)
                if (diff > MAX_DURATION_DIFF_S) continue // another version of the song, the lines would drift
                score -= diff * 4
            }
            if (norm(r.optString("trackName")) == norm(title)) score += 20
            if (norm(r.optString("artistName")) == norm(artist)) score += 15
            if (score > bestScore) {
                bestScore = score
                best = r
            }
        }
        val chosen = best ?: return null
        val synced = text(chosen, "syncedLyrics")
        if (synced.isNotBlank()) {
            val lines = Lyrics.parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true)
        }
        val plain = text(chosen, "plainLyrics")
        if (plain.isNotBlank()) {
            val lines = Lyrics.parsePlain(plain)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = false)
        }
        return if (chosen.optBoolean("instrumental", false)) Lyrics.INSTRUMENTAL else null
    }

    /** A string field that may be missing or JSON null (which `optString` would turn into "null"). */
    private fun text(o: JSONObject, name: String): String = if (o.isNull(name)) "" else o.optString(name, "")

    private fun norm(s: String): String = s.trim().lowercase()

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
