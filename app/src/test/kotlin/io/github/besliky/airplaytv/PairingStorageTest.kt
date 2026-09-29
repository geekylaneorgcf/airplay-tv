package io.github.besliky.airplaytv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStorageTest {

    @Test
    fun `only base64 encoded Ed25519 keys are accepted`() {
        assertTrue(PairedDevices.isValidKey("11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="))
        assertFalse(PairedDevices.isValidKey(""))
        assertFalse(PairedDevices.isValidKey("11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo"))
        assertFalse(PairedDevices.isValidKey("11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHUR=="))
        assertFalse(PairedDevices.isValidKey("../../etc/passwd"))
    }

    @Test
    fun `hex helpers round trip`() {
        val bytes = byteArrayOf(0, 1, 0x7f, -128, -1)
        assertEquals("00017f80ff", Identity.encodeHex(bytes))
        assertArrayEquals(bytes, Identity.decodeHex("00017F80ff"))
        assertNull(Identity.decodeHex("abc"))
        assertNull(Identity.decodeHex("zz"))
    }
}
