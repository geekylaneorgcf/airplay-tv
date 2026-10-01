package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    private val synced = "[00:01.00] one\n[00:05.00] two\n"
    private val plain = "one\ntwo\n"

    private fun candidate(
        track: String,
        artist: String,
        seconds: Double,
        synced: String = "",
        plain: String = "",
        instrumental: Boolean = false,
    ) = LyricsRepository.Candidate(track, artist, seconds, synced, plain, instrumental)

    private fun pick(vararg candidates: LyricsRepository.Candidate, title: String = "Song", artist: String = "Artist", durationMs: Long = 180_000) =
        LyricsRepository.pick(candidates.toList(), title, artist, durationMs)

    // ---- titles and artists

    @Test
    fun `decoration is stripped from titles`() {
        assertEquals("Still in Love", LyricsRepository.cleanTitle("Still in Love (feat. Starboy X)"))
        assertEquals("Still in Love", LyricsRepository.cleanTitle("Still in Love ft. Starboy X"))
        assertEquals("Heaven", LyricsRepository.cleanTitle("Heaven [Official Video]"))
        assertEquals("Self Control", LyricsRepository.cleanTitle("Self Control - Remastered 2011"))
        assertEquals("Kesariya", LyricsRepository.cleanTitle("Kesariya - From \"Brahmastra\""))
        assertEquals("Tension", LyricsRepository.cleanTitle("Tension (Tension)"))
    }

    @Test
    fun `titles without decoration are left alone`() {
        assertEquals("Mirage", LyricsRepository.cleanTitle("Mirage"))
        assertEquals("Spider-Man", LyricsRepository.cleanTitle("Spider-Man"))
        assertEquals("Self - Control", LyricsRepository.cleanTitle("Self - Control"))
        assertEquals("(Intro)", LyricsRepository.cleanTitle("(Intro)"))
    }

    @Test
    fun `artist fields are split into names`() {
        assertEquals(listOf("Jassa Dhillon", "Starboy X"), LyricsRepository.artistsOf("Jassa Dhillon & Starboy X"))
        assertEquals(listOf("Armaan Gill", "Gurlez Akhtar", "Arnaaz Gill"), LyricsRepository.artistsOf("Armaan Gill, Gurlez Akhtar & Arnaaz Gill"))
        assertEquals(listOf("Hxrmxn", "Jxggi"), LyricsRepository.artistsOf("Hxrmxn x Jxggi"))
        assertEquals(listOf("Drake", "Rihanna"), LyricsRepository.artistsOf("Drake feat. Rihanna"))
        assertEquals(listOf("AC/DC"), LyricsRepository.artistsOf("AC/DC"))
    }

    @Test
    fun `a plain title and artist make a single search`() {
        assertEquals(listOf(LyricsRepository.Query("Mirage", "Jxggi")), LyricsRepository.queriesFor("Mirage", "Jxggi"))
    }

    @Test
    fun `a decorated title is searched again as sent, cleaned, and with the first artist`() {
        assertEquals(
            listOf(
                LyricsRepository.Query("Still in Love (feat. Starboy X)", "Jassa Dhillon & Starboy X"),
                LyricsRepository.Query("Still in Love", "Jassa Dhillon & Starboy X"),
                LyricsRepository.Query("Still in Love", "Jassa Dhillon"),
            ),
            LyricsRepository.queriesFor("Still in Love (feat. Starboy X)", "Jassa Dhillon & Starboy X"),
        )
    }

    // ---- ranking

    @Test
    fun `time-stamped lyrics beat plain ones`() {
        val result = pick(
            candidate("Song", "Artist", 180.0, plain = plain),
            candidate("Song", "Artist", 181.0, synced = synced, plain = plain),
        )
        assertNotNull(result)
        assertTrue(result!!.synced)
    }

    @Test
    fun `the closest length wins among time-stamped versions`() {
        val near = "[00:01.00] near\n"
        val far = "[00:01.00] far\n"
        val result = pick(
            candidate("Song", "Artist", 186.0, synced = far),
            candidate("Song", "Artist", 180.5, synced = near),
        )
        assertEquals("near", result!!.lines.first().text)
    }

    @Test
    fun `a version of another length gives plain lyrics, not drifting timestamps`() {
        val result = pick(candidate("Song", "Artist", 200.0, synced = synced, plain = plain))
        assertNotNull(result)
        assertFalse(result!!.synced)
        assertEquals("one", result.lines.first().text)
    }

    @Test
    fun `time-stamped only lyrics of another length lose their timestamps`() {
        val result = pick(candidate("Song", "Artist", 200.0, synced = synced))
        assertNotNull(result)
        assertFalse(result!!.synced)
        assertEquals(listOf("one", "two"), result.lines.map { it.text })
        assertTrue(result.lines.all { it.timeMs < 0 })
    }

    @Test
    fun `a version far off in length is not used at all`() {
        assertNull(pick(candidate("Song", "Artist", 300.0, synced = synced, plain = plain)))
    }

    @Test
    fun `without a known length nothing is filtered by length`() {
        val result = pick(candidate("Song", "Artist", 300.0, synced = synced), durationMs = -1)
        assertNotNull(result)
        assertTrue(result!!.synced)
    }

    @Test
    fun `entries without lyrics are skipped and instrumentals are reported`() {
        assertNull(pick(candidate("Song", "Artist", 180.0)))
        val result = pick(candidate("Song", "Artist", 181.0, instrumental = true))
        assertNotNull(result)
        assertTrue(result!!.instrumental)
    }

    @Test
    fun `an exact title and artist outranks a looser match of the same kind`() {
        val wanted = "[00:01.00] wanted\n"
        val other = "[00:01.00] other\n"
        val result = pick(
            candidate("Some Other Song", "Somebody Else", 180.0, synced = other),
            candidate("Song (Official Video)", "Artist", 180.0, synced = wanted),
        )
        assertEquals("wanted", result!!.lines.first().text)
    }

    @Test
    fun `an empty lyrics body does not count as lyrics`() {
        assertNull(pick(candidate("Song", "Artist", 180.0, synced = "[ar:Artist]\n[ti:Song]\n")))
    }

    // ---- accessibility service name

    @Test
    fun `the service is named in the short form`() {
        assertEquals(
            "io.github.besliky.airplaytv/.service.SleepService",
            SleepService.component("io.github.besliky.airplaytv", "io.github.besliky.airplaytv.service.SleepService"),
        )
        assertEquals("a.b/c.d.E", SleepService.component("a.b", "c.d.E"))
    }
}
