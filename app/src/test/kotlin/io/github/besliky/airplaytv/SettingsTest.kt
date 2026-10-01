package io.github.besliky.airplaytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun `device names are trimmed and collapsed`() {
        assertEquals("Living Room TV", Settings.sanitizeName("  Living   Room\tTV  "))
        assertEquals("Kitchen", Settings.sanitizeName("Kit\u0000chen"))
    }

    @Test
    fun `empty names fall back to null`() {
        assertNull(Settings.sanitizeName(null))
        assertNull(Settings.sanitizeName("   "))
        assertNull(Settings.sanitizeName("\n\t"))
    }

    @Test
    fun `names fit into a DNS label next to the device id`() {
        val long = "Гостиная ".repeat(20)
        val name = Settings.sanitizeName(long)!!
        assertTrue(name.length <= Settings.MAX_NAME_LENGTH)
        // "<12 hex digits>@<name>" must stay within 63 bytes
        assertTrue(13 + name.toByteArray(Charsets.UTF_8).size <= 63)
        assertFalse(name.endsWith(" "))
    }

    @Test
    fun `unicode names survive`() {
        assertEquals("Телевизор 📺", Settings.sanitizeName("Телевизор 📺"))
    }

    @Test
    fun `resolution keys map to sizes`() {
        assertEquals(Settings.Resolution.HD, Settings.Resolution.fromKey("720p"))
        assertEquals(Settings.Resolution.UHD, Settings.Resolution.fromKey("4k"))
        assertEquals(Settings.Resolution.FULL_HD, Settings.Resolution.fromKey(null))
        assertEquals(Settings.Resolution.FULL_HD, Settings.Resolution.fromKey("bogus"))
        assertEquals(1920, Settings.Resolution.FULL_HD.width)
    }

    @Test
    fun `the appearance defaults to the Apple TV the receiver always was`() {
        assertEquals(Settings.Appearance.APPLE_TV, Settings.Appearance.fromKey(null))
        assertEquals(Settings.Appearance.APPLE_TV, Settings.Appearance.fromKey("bogus"))
        assertEquals("AppleTV3,2", Settings.Appearance.APPLE_TV.model)
        assertEquals("220.68", Settings.Appearance.APPLE_TV.sourceVersion)
    }

    @Test
    fun `appearances have distinct keys and cycle through all of them`() {
        val all = Settings.Appearance.entries
        assertEquals(all.size, all.map { it.key }.toSet().size)
        var current = Settings.Appearance.APPLE_TV
        val seen = HashSet<Settings.Appearance>()
        repeat(all.size) {
            seen += current
            current = current.next()
        }
        assertEquals(all.toSet(), seen)
        assertEquals(Settings.Appearance.APPLE_TV, current)
    }

    @Test
    fun `advertised identities only use characters that are safe in a TXT record`() {
        val safe = Regex("[A-Za-z0-9,._-]+")
        for (a in Settings.Appearance.entries) {
            assertTrue(a.model, safe.matches(a.model) && a.model.length < 24)
            assertTrue(a.sourceVersion, safe.matches(a.sourceVersion) && a.sourceVersion.length < 16)
        }
    }

    @Test
    fun `restart keys cover everything that is advertised`() {
        assertTrue(Settings.KEY_APPEARANCE in Settings.RESTART_KEYS)
        assertTrue(Settings.KEY_NAME in Settings.RESTART_KEYS)
        assertTrue(Settings.KEY_PIN in Settings.RESTART_KEYS)
        assertTrue(Settings.KEY_RESOLUTION in Settings.RESTART_KEYS)
        assertFalse(Settings.KEY_OVERLAY in Settings.RESTART_KEYS)
    }
}
