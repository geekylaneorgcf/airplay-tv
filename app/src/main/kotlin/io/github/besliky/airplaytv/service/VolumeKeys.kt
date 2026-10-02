package io.github.besliky.airplaytv.service

import android.view.KeyEvent

/**
 * The remote's volume keys, taken for the receiver while the system will not move its own volume.
 *
 * With HDMI-CEC on, the stick hands the volume to the TV and calls its output a fixed-volume device: Fire OS shows the key press
 * and does nothing with it, so the remote's volume buttons were dead while AirPlay played, and a volume set from the phone
 * showed nowhere. The accessibility service sees the keys before Fire OS does, so while the receiver has asked for them
 * ([take]) it hands them over, and the receiver steps the one level the phone's slider also sets. With no one taking them every
 * key goes on to the system untouched.
 */
object VolumeKeys {

    @Volatile
    private var listener: ((Int) -> Unit)? = null

    /** The receiver asks for the keys with a function that is told which one ([KeyEvent.KEYCODE_VOLUME_UP] and so on); null gives them back. */
    fun take(onKey: ((Int) -> Unit)?) {
        listener = onKey
    }

    val isTaken: Boolean get() = listener != null

    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    /**
     * Offers a key to whoever has asked for the volume keys. True when it was taken, press and release alike (the system must
     * not see either); the receiver is told of every press, a held key included, so that it keeps stepping.
     */
    fun onKey(keyCode: Int, action: Int): Boolean {
        if (!isVolumeKey(keyCode)) return false
        val taker = listener ?: return false
        if (action == KeyEvent.ACTION_DOWN) taker(keyCode)
        return true
    }
}
