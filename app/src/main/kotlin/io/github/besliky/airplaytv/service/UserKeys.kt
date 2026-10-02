package io.github.besliky.airplaytv.service

/** Who wants to hear that someone pressed a key on the remote (the volume keys apart): the receiver, to bring back a TV screen it turned off for the music. */
object UserKeys {
    @Volatile
    var listener: (() -> Unit)? = null
}
