package io.github.besliky.airplaytv.service

/**
 * The link between the receiver, which hears the sender's requests for AirPlay video, and the screen that plays the video
 * ([io.github.besliky.airplaytv.ui.VideoPlayerActivity]): the sender's requests go to the controller the screen registers, and the screen tells when it
 * ends of its own accord (Back, an error, the end of the video).
 */
object VideoPlayback {

    interface Controller {
        /** 0 pauses; anything above plays, at that speed. */
        fun setRate(rate: Double)

        fun seekTo(seconds: Double)

        /** The sender ended the session: leave the screen. */
        fun stop()
    }

    @Volatile
    var controller: Controller? = null

    /** Set by the receiver: called when the screen ended without the sender asking. */
    @Volatile
    var onScreenEnded: (() -> Unit)? = null

    /** Where the video is, for the receiver's records: the address (host only, for the log). */
    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?')
}
