package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {

    private val airplay = linkedMapOf("model" to "AppleTV3,2", "srcvers" to "220.68", "features" to "0x527FFEE6,0x400")
    private val raop = linkedMapOf("txtvers" to "1", "et" to "0,3,5", "am" to "AppleTV3,2", "vs" to "220.68")

    @Test
    fun `as an Apple TV both services are published exactly as the core wrote them`() {
        val (a, r) = Appearance.records(airplay, raop, speaker = false)
        assertEquals(airplay, a)
        assertEquals(raop, r)
    }

    @Test
    fun `as a speaker only the audio service is published`() {
        val (a, _) = Appearance.records(airplay, raop, speaker = true)
        assertTrue(a.isEmpty())
    }

    @Test
    fun `as a speaker the audio service claims another model and keeps the rest`() {
        val (_, r) = Appearance.records(airplay, raop, speaker = true)
        assertEquals(Appearance.SPEAKER_MODEL, r["am"])
        assertEquals(raop.keys.toList(), r.keys.toList())
        assertEquals("0,3,5", r["et"])
        assertEquals("220.68", r["vs"])
    }

    @Test
    fun `the Apple TV model is not the speaker model`() {
        assertTrue(!Appearance.SPEAKER_MODEL.startsWith("AppleTV"))
    }

    @Test
    fun `with no audio record there is nothing to change`() {
        val (a, r) = Appearance.records(airplay, emptyMap(), speaker = true)
        assertTrue(a.isEmpty() && r.isEmpty())
    }
}
