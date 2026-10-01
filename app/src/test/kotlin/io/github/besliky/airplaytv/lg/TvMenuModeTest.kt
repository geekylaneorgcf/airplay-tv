package io.github.besliky.airplaytv.lg

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvMenuModeTest {

    @Test
    fun `the arrow keys, OK and Back stand for the TV's buttons`() {
        assertEquals("UP", TvMenuMode.buttonFor(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("DOWN", TvMenuMode.buttonFor(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("LEFT", TvMenuMode.buttonFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("RIGHT", TvMenuMode.buttonFor(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("ENTER", TvMenuMode.buttonFor(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("ENTER", TvMenuMode.buttonFor(KeyEvent.KEYCODE_ENTER))
        assertEquals("BACK", TvMenuMode.buttonFor(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `volume, media and Home keys stay the stick's`() {
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU)) {
            assertNull(TvMenuMode.buttonFor(key))
        }
    }
}
