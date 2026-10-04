package io.github.besliky.airplaytv.lg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvSettingsApiTest {

    @Test
    fun `the picture mode is read from the settings answer`() {
        val answer = JSONObject("""{"type":"response","payload":{"returnValue":true,"category":"picture","settings":{"pictureMode":"cinema"}}}""")
        assertEquals("cinema", TvPicture.read(answer))
        assertNull(TvPicture.read(JSONObject("""{"payload":{"returnValue":false}}""")))
    }

    @Test
    fun `an unknown picture mode shows as the TV names it`() {
        assertEquals("Cinema", TvPicture.label("cinema"))
        assertEquals("hdrEffect", TvPicture.label("hdrEffect"))
        assertEquals("Unknown", TvPicture.label(null))
    }

    @Test
    fun `the modes of the HDR families are told from the plain ones`() {
        assertEquals(TvPicture.Family.SDR, TvPicture.familyOf("cinema"))
        assertEquals(TvPicture.Family.SDR, TvPicture.familyOf("hdrEffect"))
        assertEquals(TvPicture.Family.SDR, TvPicture.familyOf(null))
        assertEquals(TvPicture.Family.HDR, TvPicture.familyOf("hdrCinema"))
        assertEquals(TvPicture.Family.DOLBY, TvPicture.familyOf("dolbyHdrGame"))
    }

    @Test
    fun `the mode the owner's TV reports is named for people`() {
        assertEquals("Dolby Vision Game", TvPicture.label("dolbyHdrGame"))
        assertEquals("HDR Cinema", TvPicture.label("hdrCinema"))
        assertEquals("Dolby Vision Bright", TvPicture.label("dolbyHdrBright"))
        assertEquals("Filmmaker", TvPicture.label("filmMaker"))
        assertEquals("dolbyHdrSomethingNew", TvPicture.label("dolbyHdrSomethingNew"))
    }

    @Test
    fun `a scene's plain mode is turned into the mode of the signal the TV shows`() {
        assertEquals("cinema", TvPicture.translate("cinema", "standard"))
        assertEquals("dolbyHdrGame", TvPicture.translate("game", "dolbyHdrCinema"))
        assertEquals("dolbyHdrBright", TvPicture.translate("standard", "dolbyHdrGame"))
        assertEquals("hdrFilmMaker", TvPicture.translate("filmMaker", "hdrGame"))
        assertEquals("hdrCinema", TvPicture.translate("hdrCinema", "hdrGame"))
        assertEquals("cinema", TvPicture.translate("hdrCinema", "standard"))
        assertEquals("game", TvPicture.translate("dolbyHdrGame", null))
    }

    @Test
    fun `the panel cycles through the modes of the family the TV is in`() {
        assertEquals(listOf("dolbyHdrCinema", "dolbyHdrBright", "dolbyHdrDark", "dolbyHdrVivid", "dolbyHdrGame"), TvPicture.modesFor(TvPicture.Family.DOLBY))
        assertEquals("standard", TvPicture.modesFor(TvPicture.Family.SDR).first())
        assertEquals("hdrStandard", TvPicture.modesFor(TvPicture.Family.HDR).first())
    }

    @Test
    fun `an OLED brightness is read as a number, whatever way the TV writes it`() {
        assertEquals(80, TvPicture.readNumber(JSONObject("""{"payload":{"settings":{"oledLight":80}}}"""), "oledLight"))
        assertEquals(60, TvPicture.readNumber(JSONObject("""{"payload":{"settings":{"backlight":"60"}}}"""), "backlight"))
        assertNull(TvPicture.readNumber(JSONObject("""{"payload":{"settings":{"backlight":"x"}}}"""), "backlight"))
        assertNull(TvPicture.readNumber(JSONObject("""{"payload":{"settings":{}}}"""), "oledLight"))
        assertNull(TvPicture.readNumber(JSONObject("""{"payload":{"returnValue":false}}"""), "oledLight"))
        assertEquals(listOf("oledLight", "backlight"), TvPicture.BRIGHTNESS_KEYS)
    }

    @Test
    fun `a brightness write names the setting and the number`() {
        val request = TvPicture.writeNumberRequest("oledLight", 30)
        assertEquals("picture", request.getString("category"))
        assertEquals(30, request.getJSONObject("settings").getInt("oledLight"))
        assertEquals("backlight", TvPicture.readKeyRequest("backlight").getJSONArray("keys").getString(0))
    }

    @Test
    fun `the picture write asks for the picture category`() {
        val request = TvPicture.writeRequest("game")
        assertEquals("picture", request.getString("category"))
        assertEquals("game", request.getJSONObject("settings").getString("pictureMode"))
        assertEquals("pictureMode", TvPicture.readRequest().getJSONArray("keys").getString(0))
    }

    @Test
    fun `the sound output is read and written`() {
        assertEquals("external_arc", TvSound.read(JSONObject("""{"payload":{"returnValue":true,"soundOutput":"external_arc"}}""")))
        assertEquals("HDMI ARC", TvSound.label("external_arc"))
        assertEquals("tv_speaker", TvSound.writeRequest("tv_speaker").getString("output"))
    }

    @Test
    fun `the alert that reaches the settings service carries the call three times`() {
        val params = TvPicture.writeRequest("expert2")
        val alert = TvLuna.alertRequest(TvLuna.SET_SYSTEM_SETTINGS, params)
        assertEquals(TvLuna.SET_SYSTEM_SETTINGS, alert.getJSONArray("buttons").getJSONObject(0).getString("onClick"))
        assertEquals(TvLuna.SET_SYSTEM_SETTINGS, alert.getJSONObject("onclose").getString("uri"))
        assertEquals(TvLuna.SET_SYSTEM_SETTINGS, alert.getJSONObject("onfail").getString("uri"))
        assertEquals("expert2", alert.getJSONObject("onclose").getJSONObject("params").getJSONObject("settings").getString("pictureMode"))
    }

    @Test
    fun `the alert id is read from the answer`() {
        assertEquals("a1", TvLuna.alertId(JSONObject("""{"payload":{"returnValue":true,"alertId":"a1"}}""")))
        assertNull(TvLuna.alertId(JSONObject("""{"payload":{"returnValue":false}}""")))
        assertNull(TvLuna.alertId(null))
    }

    @Test
    fun `cycle wraps both ways and starts at an end for a value it does not know`() {
        val list = listOf("a", "b", "c")
        assertEquals("b", cycle(list, "a", 1))
        assertEquals("a", cycle(list, "c", 1))
        assertEquals("c", cycle(list, "a", -1))
        assertEquals("a", cycle(list, "zzz", 1))
        assertEquals("c", cycle(list, null, -1))
    }
}
