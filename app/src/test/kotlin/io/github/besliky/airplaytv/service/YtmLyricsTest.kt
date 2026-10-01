package io.github.besliky.airplaytv.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The YouTube Music lookup, played against answers in the shape the real service gives (with invented songs). */
class YtmLyricsTest {

    // a search answer with two songs, a song's page with a lyrics tab, and a lyrics page
    private val search = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Midnight on the Harbour"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"The Paper Lanterns"},{"text":" & "},{"text":"Mira Voss"},{"text":" • "},{"text":"Slow Tide"},{"text":" • "},{"text":"2:36"}],"accessibility":{"accessibilityData":{"label":"The Paper Lanterns & Mira Voss • Slow Tide • 2 minutes, 36 seconds"}}}}}],"playlistItemData":{"videoId":"vid11111111"}}},{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Midnight on the Harbour (Live)"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Another Band"},{"text":" • "},{"text":"Live Sessions"}],"accessibility":{"accessibilityData":{"label":"The Paper Lanterns & Jay Trak • Wavy • 2 minutes, 41 seconds"}}}}}],"playlistItemData":{"videoId":"vid22222222"}}}]}}]}}}}]}}}"""
    private val next = """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"title":"Up next","content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoRenderer":{"title":{"runs":[{"text":"Midnight on the Harbour"}]},"lengthText":{"runs":[{"text":"2:36"}],"accessibility":{"accessibilityData":{"label":"2 minutes, 36 seconds"}}},"videoId":"vid11111111","selected":true,"shortBylineText":{"runs":[{"text":"The Paper Lanterns"},{"text":" & "},{"text":"Mira Voss"}]}}}]}}}}}},{"tabRenderer":{"title":"Lyrics","endpoint":{"browseEndpoint":{"browseId":"MPLYt_test"}}}}]}}}}}"""
    private val browse = """{"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{"header":{"runs":[{"text":"Lyrics"}]},"description":{"runs":[{"text":"Neon rivers run beneath the sleeping town\nEvery window holds a story folded small\n\nWe were chasing echoes down the stairs\nCounting streetlights like they were stars"}]},"footer":{"runs":[{"text":"Source: test"}]}}}]}}}"""

    @Test
    fun `a search answer gives its songs with their title, byline and id`() {
        val songs = YtmLyrics.parseSearch(search)
        assertEquals(2, songs.size)
        assertEquals("vid11111111", songs[0].videoId)
        assertEquals("Midnight on the Harbour", songs[0].title)
        assertTrue(songs[0].byline.startsWith("The Paper Lanterns & Mira Voss \u2022 Slow Tide"))
        assertEquals("Another Band \u2022 Live Sessions", songs[1].byline)
    }

    @Test
    fun `something that is not a search answer gives no songs`() {
        assertTrue(YtmLyrics.parseSearch("").isEmpty())
        assertTrue(YtmLyrics.parseSearch("not json").isEmpty())
        assertTrue(YtmLyrics.parseSearch("{}").isEmpty())
    }

    @Test
    fun `the song asked for is recognised by its title and one of its artists`() {
        val songs = YtmLyrics.parseSearch(search)
        assertTrue(YtmLyrics.matches(songs[0], "Midnight on the Harbour", "The Paper Lanterns"))
        assertTrue(YtmLyrics.matches(songs[0], "Midnight on the Harbour (feat. Mira Voss)", "Mira Voss, The Paper Lanterns"))
        assertTrue(YtmLyrics.matches(songs[0], "midnight on the harbour", "the paper lanterns"))
    }

    @Test
    fun `another song with a similar title is not taken for it`() {
        val songs = YtmLyrics.parseSearch(search)
        assertFalse("another artist", YtmLyrics.matches(songs[1], "Midnight on the Harbour", "The Paper Lanterns"))
        assertFalse("another title", YtmLyrics.matches(songs[0], "Midnight", "The Paper Lanterns"))
        assertFalse(YtmLyrics.matches(songs[0], "", "The Paper Lanterns"))
    }

    @Test
    fun `a song page gives where its lyrics are and how long it is`() {
        val page = YtmLyrics.parseNext(next, "vid11111111")
        assertEquals("MPLYt_test", page.lyricsBrowseId)
        assertEquals(156, page.lengthS)
    }

    @Test
    fun `a page for another song has no length`() {
        assertEquals(-1, YtmLyrics.parseNext(next, "other").lengthS)
    }

    @Test
    fun `a song whose lyrics tab is greyed out has no lyrics`() {
        val greyed = next.replace("\"title\":\"Lyrics\"", "\"title\":\"Lyrics\",\"unselectable\":true")
        assertNull(YtmLyrics.parseNext(greyed, "vid11111111").lyricsBrowseId)
    }

    @Test
    fun `a page without a lyrics tab has none`() {
        assertNull(YtmLyrics.parseNext("""{"contents":{}}""", "x").lyricsBrowseId)
        assertNull(YtmLyrics.parseNext("garbage", "x").lyricsBrowseId)
    }

    @Test
    fun `the lyrics page gives the text`() {
        val text = YtmLyrics.parseLyrics(browse)
        assertNotNull(text)
        assertTrue(text!!.startsWith("Neon rivers run beneath the sleeping town"))
        assertTrue(text.contains("\n\n"))
    }

    @Test
    fun `a page without lyrics gives nothing`() {
        assertNull(YtmLyrics.parseLyrics("{}"))
        assertNull(YtmLyrics.parseLyrics("nope"))
    }

    @Test
    fun `lengths are read from minutes and seconds`() {
        assertEquals(156, YtmLyrics.seconds("2:36"))
        assertEquals(3723, YtmLyrics.seconds("1:02:03"))
        assertEquals(-1, YtmLyrics.seconds("236"))
        assertEquals(-1, YtmLyrics.seconds("a:b"))
        assertEquals(-1, YtmLyrics.seconds(null))
    }

    /** A service that answers each endpoint with a canned page and records what it was asked. */
    private class FakeService(val search: String?, val next: String?, val browse: String?) {
        val asked = ArrayList<String>()
        val queries = ArrayList<String>()

        fun post(endpoint: String, body: JSONObject): String? {
            asked += endpoint
            if (endpoint == "search") queries += body.getString("query")
            return when (endpoint) {
                "search" -> search
                "next" -> next
                "browse" -> browse
                else -> null
            }
        }
    }

    @Test
    fun `a song is looked up in three steps and its plain lyrics come back`() {
        val service = FakeService(search, next, browse)
        val lyrics = YtmLyrics.find("Midnight on the Harbour", "The Paper Lanterns", 156_000, service::post)
        assertNotNull(lyrics)
        assertFalse("not time-stamped", lyrics!!.synced)
        assertEquals("Neon rivers run beneath the sleeping town", lyrics.lines[0].text)
        assertEquals(listOf("search", "next", "browse"), service.asked)
        assertEquals(listOf("Midnight on the Harbour The Paper Lanterns"), service.queries)
    }

    @Test
    fun `the search is made with the cleaned title and the first artist only`() {
        val service = FakeService(search, next, browse)
        YtmLyrics.find("Midnight on the Harbour (feat. Mira Voss)", "The Paper Lanterns & Mira Voss", 156_000, service::post)
        assertEquals(listOf("Midnight on the Harbour The Paper Lanterns"), service.queries)
    }

    @Test
    fun `a song of a very different length is not used`() {
        val service = FakeService(search, next, browse)
        assertNull(YtmLyrics.find("Midnight on the Harbour", "The Paper Lanterns", 400_000, service::post))
        assertEquals("it never asks for the lyrics", listOf("search", "next"), service.asked)
    }

    @Test
    fun `an unknown length of the track does not stop the lookup`() {
        val service = FakeService(search, next, browse)
        assertNotNull(YtmLyrics.find("Midnight on the Harbour", "The Paper Lanterns", -1, service::post))
    }

    @Test
    fun `a song that is not in the answer finds nothing and costs one request`() {
        val service = FakeService(search, next, browse)
        assertNull(YtmLyrics.find("A Different Song", "The Paper Lanterns", 156_000, service::post))
        assertEquals(listOf("search"), service.asked)
    }

    @Test
    fun `no answer from the service finds nothing`() {
        assertNull(YtmLyrics.find("Midnight on the Harbour", "The Paper Lanterns", 156_000, FakeService(null, null, null)::post))
        assertNull(YtmLyrics.find("Midnight on the Harbour", "The Paper Lanterns", 156_000, FakeService(search, null, null)::post))
        assertNull(YtmLyrics.find("Midnight on the Harbour", "The Paper Lanterns", 156_000, FakeService(search, next, null)::post))
    }

    @Test
    fun `a blank title finds nothing without asking`() {
        val service = FakeService(search, next, browse)
        assertNull(YtmLyrics.find("", "", 0, service::post))
        assertTrue(service.asked.isEmpty())
    }
}
