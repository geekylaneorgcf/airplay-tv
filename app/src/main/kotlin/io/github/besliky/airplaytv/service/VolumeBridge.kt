package io.github.besliky.airplaytv.service

import android.view.KeyEvent

/** The way the mini-remote steps the volume: the receiver sets [listener] and does what a press of the remote's volume key would do. */
object VolumeBridge {
    @Volatile
    var listener: ((Int) -> Unit)? = null

    fun step(up: Boolean) {
        listener?.invoke(if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN)
    }
}
