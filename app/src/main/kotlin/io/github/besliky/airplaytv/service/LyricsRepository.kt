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
 * title and artist are sent. If the title as sent finds nothing, it is tried again without the
 * decoration streaming services add ("(feat. …)", "- Remastered 2011"), then with only the first
 * artist. Candidates are ranked by whether they have time-stamped lines, how close their length is to
 * the track's, and an exact title/artist match. Results, including "nothing found", are cached for the
 * life of the process; a lookup that failed (no network, server error) is not.
 *
 * This is the only place in the app that talks to the internet, and it is only used when the user
 * turns Lyrics on.
 */
object LyricsRepository {

    /** What a lookup came back with. [Failed] means the service could not be asked: worth trying again later. */
    sealed interface Outcome {
        class Found(val lyrics: Lyrics) : Outcome
        object NotFound : Outcome
        object Failed : Outcome
    }

    /** One entry of a search answer, reduced to what ranking needs. */
    class Candidate(
        val trackName: String,
        val artistName: String,
        val durationS: Double,
        val synced: String,
        val plain: String,
        val instrumental: Boolean,
    )

    /** A search to run: the track name and, when there is one, the artist. */
    data class Query(val track: String, val artist: String)

    private const val TAG = "AirPlayTV-Lyrics"
    private const val SEARCH_URL = "https://lrclib.net/api/search"
    private const val MAX_RESPONSE_BYTES = 3_000_000
    private const val CACHE_SIZE = 40

    /** Time-stamped lines drift when the version is longer or shorter than the track, so they need a close match. */
    internal const val MAX_SYNCED_DIFF_S = 10.0

    /** Plain lines do not drift: another cut of the same song still has the same words. */
    internal const val MAX_PLAIN_DIFF_S = 45.0

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lyrics").apply { isDaemon = true }
    }

    private val cache = object : LinkedHashMap<String, Lyrics?>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?): Boolean = size > CACHE_SIZE
    }

    /**
     * Looks up lyrics. With [youtubeMusic] on, a song LRCLIB has nothing for is also looked up on YouTube Music (plain
     * text only). [onResult] runs on a background thread.
     */
    fun load(title: String, artist: String, durationMs: Long, version: String, youtubeMusic: Boolean = false, onResult: (Outcome) -> Unit) {
        val key = "${norm(title)}|${norm(artist)}|${durationMs / 5000}|${if (youtubeMusic) "y" else "n"}"
        val cached: Lyrics?
        val hit: Boolean
        synchronized(cache) {
            hit = cache.containsKey(key)
            cached = cache[key]
        }
        executor.execute {
            if (hit) {
                onResult(if (cached != null) Outcome.Found(cached) else Outcome.NotFound)
                return@execute
            }
            val outcome = try {
                lookUp(title, artist, durationMs, version, youtubeMusic)
            } catch (e: Exception) {
                Log.w(TAG, "lyrics lookup failed: ${e.javaClass.simpleName}")
                Outcome.Failed
            }
            if (outcome !is Outcome.Failed) {
                synchronized(cache) { cache[key] = (outcome as? Outcome.Found)?.lyrics }
            }
            onResult(outcome)
        }
    }

    private fun lookUp(title: String, artist: String, durationMs: Long, version: String, youtubeMusic: Boolean): Outcome {
        for ((index, query) in queriesFor(title, artist).withIndex()) {
            val candidates = search(query, version) ?: return Outcome.Failed
            val picked = pick(candidates, title, artist, durationMs)
            Log.i(TAG, "lookup ${index + 1}: ${candidates.size} candidates, ${if (picked == null) "none usable" else if (picked.synced) "picked synced lyrics" else "picked plain lyrics"}")
            if (picked != null) return Outcome.Found(picked)
        }
        if (youtubeMusic) {
            // LRCLIB answered and has nothing; a failure of this second source is just "nothing found", the first one is the one to retry
            val found = try {
                YtmLyrics.find(title, artist, durationMs) { endpoint, body -> YtmLyrics.post(endpoint, body, version) }
            } catch (e: Exception) {
                Log.w(TAG, "YouTube Music lookup failed: ${e.javaClass.simpleName}")
                null
            }
            Log.i(TAG, "YouTube Music: ${if (found == null) "no lyrics" else "plain lyrics found"}")
            if (found != null) return Outcome.Found(found)
        }
        return Outcome.NotFound
    }

    /** Runs one search; null when the service did not answer properly (rate limit, server error). */
    private fun search(query: Query, version: String): List<Candidate>? {
        val url = URL("$SEARCH_URL?track_name=${encode(query.track)}&artist_name=${encode(query.artist)}")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 9000
        connection.setRequestProperty("User-Agent", "AirPlayTV-fork/$version (https://github.com/geekylaneorgcf/airplay-tv)")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "lookup answered HTTP $code")
                // A request the service rejects will be rejected again; rate limits and outages pass.
                return if (code in 400..499 && code != 408 && code != 429) emptyList() else null
            }
            return parse(connection.inputStream.use { readLimited(it) })
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): List<Candidate> {
        val array = JSONArray(body)
        val out = ArrayList<Candidate>(array.length())
        for (i in 0 until array.length()) {
            val r = array.optJSONObject(i) ?: continue
            out += Candidate(
                trackName = text(r, "trackName"),
                artistName = text(r, "artistName"),
                durationS = r.optDouble("duration", -1.0),
                synced = text(r, "syncedLyrics"),
                plain = text(r, "plainLyrics"),
                instrumental = r.optBoolean("instrumental", false),
            )
        }
        return out
    }

    internal fun readLimited(stream: InputStream, max: Int = MAX_RESPONSE_BYTES): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("response too large")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    // ---------------------------------------------------------------- query planning and ranking

    private val brackets = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val strayBrackets = Regex("""[()\[\]]""")
    private val featuring = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val versionSuffix = Regex(
        """\s+-\s+.*\b(?:remaster(?:ed)?|version|edit|mix|live|from|official|video|audio|lyrics?|visuali[sz]er|soundtrack|ost|single)\b.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val artistSeparators = Regex(
        """\s*(?:,|&|;|\s+/\s+|\s+x\s+|\s+and\s+|\s+(?:feat\.?|ft\.?|featuring|with)\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** The title without "(feat. …)", "[Official Video]", "- Remastered 2011" and the like; the original if nothing is left. */
    internal fun cleanTitle(title: String): String {
        var t = brackets.replace(title.trim(), "")
        t = featuring.replace(t, "")
        t = versionSuffix.replace(t, "")
        t = strayBrackets.replace(t, "").replace(Regex("""\s+"""), " ").trim()
        return t.ifEmpty { title.trim() }
    }

    /** The names in an artist field such as "A, B & C feat. D". */
    internal fun artistsOf(artist: String): List<String> =
        artist.split(artistSeparators).map { it.trim() }.filter { it.isNotEmpty() }

    /** The searches to try, in order, without repeats: as sent, with a cleaned title, with the first artist only. */
    internal fun queriesFor(title: String, artist: String): List<Query> {
        val cleaned = cleanTitle(title)
        val first = artistsOf(artist).firstOrNull() ?: artist.trim()
        return listOf(Query(title.trim(), artist.trim()), Query(cleaned, artist.trim()), Query(cleaned, first)).distinct()
    }

    /** Lower-case letters and digits only, so "Still In Love" and "still-in-love" compare equal. */
    private fun simple(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Picks the best usable lyrics among [candidates] for a track of [durationMs] (unknown if not positive).
     * Time-stamped lyrics are only used for a version within [MAX_SYNCED_DIFF_S] of the track's length;
     * a longer or shorter version still gives its words as plain lyrics, up to [MAX_PLAIN_DIFF_S].
     */
    internal fun pick(candidates: List<Candidate>, title: String, artist: String, durationMs: Long): Lyrics? {
        val want = if (durationMs > 0) durationMs / 1000.0 else -1.0
        val wantedTitle = simple(cleanTitle(title))
        val wantedArtists = artistsOf(artist).map(::simple).filter { it.isNotEmpty() }

        class Ranked(val candidate: Candidate, val score: Double, val mode: Int)

        val ranked = ArrayList<Ranked>()
        for (c in candidates) {
            val diff = if (want > 0 && c.durationS > 0) abs(c.durationS - want) else 0.0
            val hasSynced = c.synced.isNotBlank()
            val hasPlain = c.plain.isNotBlank()
            val (base, mode) = when {
                hasSynced && diff <= MAX_SYNCED_DIFF_S -> (100.0 - diff * 4) to SYNCED
                hasPlain && diff <= MAX_PLAIN_DIFF_S -> (40.0 - diff * 0.5) to PLAIN
                hasSynced && diff <= MAX_PLAIN_DIFF_S -> (35.0 - diff * 0.5) to UNSTAMPED
                c.instrumental && !hasSynced && !hasPlain && diff <= MAX_SYNCED_DIFF_S -> (10.0 - diff) to INSTRUMENTAL
                else -> continue
            }
            var score = base
            if (simple(cleanTitle(c.trackName)) == wantedTitle) score += 20
            val candidateArtist = simple(c.artistName)
            if (wantedArtists.any { it in candidateArtist }) score += 15
            ranked += Ranked(c, score, mode)
        }
        for (r in ranked.sortedByDescending { it.score }) {
            val lyrics = when (r.mode) {
                SYNCED -> Lyrics.parseLrc(r.candidate.synced).takeIf { it.isNotEmpty() }?.let { Lyrics(it, synced = true) }
                PLAIN -> Lyrics.parsePlain(r.candidate.plain).takeIf { it.isNotEmpty() }?.let { Lyrics(it, synced = false) }
                UNSTAMPED -> Lyrics.parseLrc(r.candidate.synced).takeIf { it.isNotEmpty() }
                    ?.let { lines -> Lyrics(lines.map { Lyrics.Line(-1, it.text) }, synced = false) }
                else -> Lyrics.INSTRUMENTAL
            }
            if (lyrics != null) return lyrics
        }
        return null
    }

    private const val SYNCED = 0
    private const val PLAIN = 1
    private const val UNSTAMPED = 2
    private const val INSTRUMENTAL = 3

    /** A string field that may be missing or JSON null (which `optString` would turn into "null"). */
    private fun text(o: JSONObject, name: String): String = if (o.isNull(name)) "" else o.optString(name, "")

    private fun norm(s: String): String = s.trim().lowercase()

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
