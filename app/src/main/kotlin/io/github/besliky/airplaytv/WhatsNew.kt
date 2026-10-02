package io.github.besliky.airplaytv

/**
 * The few plain lines shown once after an update, from `res/raw/whats_new.txt`, one line each, describing the version they ship
 * with. A version that has been shown is not shown again.
 */
object WhatsNew {

    /** The lines of [text], without empty ones; null when there are none. */
    fun parse(text: String?): List<String>? =
        text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    /** Whether to show the notes: there are some, and this version has not shown them yet. */
    fun shouldShow(seenVersion: String?, currentVersion: String, lines: List<String>?): Boolean =
        !lines.isNullOrEmpty() && seenVersion != currentVersion
}
