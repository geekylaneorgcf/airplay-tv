package io.github.besliky.airplaytv

import android.content.Context

/**
 * Senders that completed PIN pairing. Each entry is the sender's public Ed25519 key
 * (base64) with the device name it reported; only the key is used for decisions.
 */
class PairedDevices(context: Context) {

    data class Device(val key: String, val name: String)

    private val prefs = context.applicationContext.getSharedPreferences("paired_devices", Context.MODE_PRIVATE)

    fun all(): List<Device> = prefs.all.mapNotNull { (key, value) ->
        if (isValidKey(key)) Device(key, value as? String ?: "") else null
    }.sortedBy { it.name.lowercase() }

    fun keys(): Array<String> = all().map { it.key }.toTypedArray()

    fun add(key: String, name: String) {
        if (!isValidKey(key)) return
        val limitedName = name.take(64)
        val current = prefs.all
        val editor = prefs.edit()
        if (!current.containsKey(key) && current.size >= MAX_DEVICES) {
            // forget the alphabetically first entry to make room; the list is only a convenience
            current.keys.minOrNull()?.let(editor::remove)
        }
        editor.putString(key, limitedName).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    val count: Int get() = all().size

    companion object {
        private const val MAX_DEVICES = 64
        private val KEY_PATTERN = Regex("^[A-Za-z0-9+/]{43}=$")

        fun isValidKey(key: String): Boolean = KEY_PATTERN.matches(key)
    }
}
