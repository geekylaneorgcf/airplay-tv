package io.github.besliky.airplaytv.lg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvScenesTest {

    private fun input(app: String, label: String, connected: Boolean = true) = LgFacts.Input(app, label, connected)

    @Test
    fun `a scene survives being stored`() {
        val scene = TvScenes.Scene("movie", "HDMI_2", "cinema", "external_arc", 12, true)
        assertEquals(scene, TvScenes.parse("movie", TvScenes.encode(scene)))
    }

    @Test
    fun `nothing stored, or something unreadable, gives the default`() {
        assertEquals(TvScenes.default("game"), TvScenes.parse("game", null))
        assertEquals(TvScenes.default("game"), TvScenes.parse("game", "junk"))
        assertEquals(TvScenes.default("game"), TvScenes.parse("game", "a|b|c|x|0"))
        assertEquals(TvScenes.default("game"), TvScenes.parse("game", "|||500|0"))
    }

    @Test
    fun `the defaults do what their names say`() {
        assertEquals("cinema", TvScenes.default("movie").picture)
        assertEquals("ps5", TvScenes.default("game").input)
        assertEquals("game", TvScenes.default("game").picture)
        assertEquals(true, TvScenes.default("music").screenOff)
        assertEquals(8, TvScenes.default("night").volume)
    }

    @Test
    fun `the PlayStation is found by the name the TV gives its input`() {
        val inputs = listOf(input("com.webos.app.hdmi1", "Fire TV"), input("com.webos.app.hdmi2", "PS5"), input("com.webos.app.hdmi3", "HDMI3", false))
        assertEquals("HDMI_2", TvScenes.resolveInput("ps5", inputs))
    }

    @Test
    fun `a connected PlayStation input wins over one with nothing on it`() {
        val inputs = listOf(input("com.webos.app.hdmi3", "PS4", false), input("com.webos.app.hdmi2", "PlayStation 5"))
        assertEquals("HDMI_2", TvScenes.resolveInput("ps5", inputs))
    }

    @Test
    fun `no PlayStation name means no input, and an explicit input is taken as it is`() {
        assertNull(TvScenes.resolveInput("ps5", listOf(input("com.webos.app.hdmi1", "Fire TV"))))
        assertNull(TvScenes.resolveInput("", emptyList()))
        assertEquals("HDMI_3", TvScenes.resolveInput("HDMI_3", emptyList()))
    }

    @Test
    fun `a scene never raises the volume`() {
        assertEquals(8, TvScenes.cappedVolume(15, 8))
        assertNull(TvScenes.cappedVolume(5, 8))
        assertNull(TvScenes.cappedVolume(8, 8))
        assertNull(TvScenes.cappedVolume(15, -1))
        assertNull(TvScenes.cappedVolume(null, 8))
    }
}
