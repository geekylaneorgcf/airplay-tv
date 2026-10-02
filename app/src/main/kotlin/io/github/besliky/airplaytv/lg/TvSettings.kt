package io.github.besliky.airplaytv.lg

import io.github.besliky.airplaytv.Settings

/**
 * What the TV Settings Button needs to know about the set-up (is it on, is it paired) and how to talk to the TV
 * safely. The Menu button itself is handled by [TvMenuMode].
 */
object TvSettings {

    enum class State { OFF, NEEDS_SETUP, READY }

    /** The TV has been paired (it gave a key, its certificate is pinned and its address is known), whether or not the Menu button uses it. */
    fun paired(settings: Settings): Boolean =
        settings.lgClientKey.isNotEmpty() && settings.lgCertificate.isNotEmpty() && settings.lgHost.isNotEmpty()

    fun state(settings: Settings): State = when {
        !settings.tvMenuButton -> State.OFF
        settings.lgClientKey.isEmpty() || settings.lgCertificate.isEmpty() -> State.NEEDS_SETUP
        else -> State.READY
    }

    /**
     * A connector that checks the TV's certificate against the one the owner pinned. A TV that does not even
     * accept a connection within [connectTimeoutMs] is treated as off.
     */
    fun connector(settings: Settings, connectTimeoutMs: Int = 6000): LgTv.Connector {
        val trust = TvTrust(TvTrust.decode(settings.lgCertificate))
        return LgTv.Connector { host, port, secure, path ->
            WebSocketClient.connect(host, port, secure, path, trust, connectTimeoutMs = connectTimeoutMs)
        }
    }
}
