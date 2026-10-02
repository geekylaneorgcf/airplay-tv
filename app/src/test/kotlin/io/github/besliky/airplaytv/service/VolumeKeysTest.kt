package io.github.besliky.airplaytv.service

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeysTest {

    private val seen = ArrayList<Int>()

    @After
    fun giveBack() = VolumeKeys.take(null)

    @Test
    fun `with no one taking them every key goes on`() {
        assertFalse(VolumeKeys.isTaken)
        assertFalse(VolumeKeys.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertFalse(VolumeKeys.onKey(KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.ACTION_UP))
    }

    @Test
    fun `a taker is told of each press and the release is taken without a call`() {
        VolumeKeys.take { seen += it }
        assertTrue(VolumeKeys.isTaken)
        assertTrue(VolumeKeys.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertTrue(VolumeKeys.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN)) // held: the key repeats
        assertTrue(VolumeKeys.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP))
        assertTrue(VolumeKeys.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
        assertTrue(VolumeKeys.onKey(KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.ACTION_DOWN))
        assertEquals(
            listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE),
            seen,
        )
    }

    @Test
    fun `other keys are never taken`() {
        VolumeKeys.take { seen += it }
        assertFalse(VolumeKeys.onKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN))
        assertFalse(VolumeKeys.onKey(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `giving the keys back lets them go on again`() {
        VolumeKeys.take { seen += it }
        VolumeKeys.take(null)
        assertFalse(VolumeKeys.isTaken)
        assertFalse(VolumeKeys.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
        assertTrue(seen.isEmpty())
    }
}
