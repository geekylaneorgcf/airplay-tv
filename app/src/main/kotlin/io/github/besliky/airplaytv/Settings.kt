package io.github.besliky.airplaytv

import android.content.Context
import android.content.SharedPreferences

/** User settings, stored in the app's private preferences. */
class Settings(context: Context) {

    enum class Resolution(val key: String, val width: Int, val height: Int) {
        HD("720p", 1280, 720),
        FULL_HD("1080p", 1920, 1080),
        UHD("4k", 3840, 2160);

        companion object {
            fun fromKey(key: String?): Resolution = entries.firstOrNull { it.key == key } ?: FULL_HD
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var deviceName: String
        get() = sanitizeName(prefs.getString(KEY_NAME, null)) ?: DEFAULT_NAME
        set(value) = prefs.edit().putString(KEY_NAME, sanitizeName(value) ?: DEFAULT_NAME).apply()

    /** Look up lyrics on lrclib.net for the playing song. Off by default: it is the only thing that uses the internet. */
    var lyricsEnabled: Boolean
        get() = prefs.getBoolean(KEY_LYRICS, false)
        set(value) = prefs.edit().putBoolean(KEY_LYRICS, value).apply()

    /** When LRCLIB has no lyrics for a song, also ask YouTube Music (plain lyrics only). Off by default: it sends the title and artist to Google. */
    var lyricsYoutubeMusic: Boolean
        get() = prefs.getBoolean(KEY_LYRICS_YTM, false)
        set(value) = prefs.edit().putBoolean(KEY_LYRICS_YTM, value).apply()

    /**
     * Shifts the lyrics by hand, in milliseconds: positive shows each line earlier, negative later. For what the
     * automatic alignment cannot catch (a song cut differently from its lyrics).
     */
    var lyricsOffsetMs: Int
        get() = prefs.getInt(KEY_LYRICS_OFFSET, 0).let { if (it in LYRICS_OFFSET_CHOICES) it else 0 }
        set(value) = prefs.edit().putInt(KEY_LYRICS_OFFSET, if (value in LYRICS_OFFSET_CHOICES) value else 0).apply()

    /** Minutes to wait after the music stops before the TV is put back to sleep; 0 = never. Only used when AirPlay woke it. */
    var sleepAfterMinutes: Int
        get() = prefs.getInt(KEY_SLEEP, 0).let { if (it in SLEEP_CHOICES) it else 0 }
        set(value) = prefs.edit().putInt(KEY_SLEEP, if (value in SLEEP_CHOICES) value else 0).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var requirePin: Boolean
        get() = prefs.getBoolean(KEY_PIN, false)
        set(value) = prefs.edit().putBoolean(KEY_PIN, value).apply()

    var startAutomatically: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    var showConnectionInfo: Boolean
        get() = prefs.getBoolean(KEY_CONNECTION_INFO, true)
        set(value) = prefs.edit().putBoolean(KEY_CONNECTION_INFO, value).apply()

    var performanceOverlay: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    var verboseLogging: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE, false)
        set(value) = prefs.edit().putBoolean(KEY_VERBOSE, value).apply()

    var resolution: Resolution
        get() = Resolution.fromKey(prefs.getString(KEY_RESOLUTION, null))
        set(value) = prefs.edit().putString(KEY_RESOLUTION, value.key).apply()

    var frameRate: Int
        get() = if (prefs.getInt(KEY_FPS, 60) == 30) 30 else 60
        set(value) = prefs.edit().putInt(KEY_FPS, if (value == 30) 30 else 60).apply()

    /** Decoder name chosen by the user, empty for automatic selection. */
    var preferredDecoder: String
        get() = prefs.getString(KEY_DECODER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DECODER, value).apply()

    /**
     * Bring the player back by itself: when the screensaver would start while music plays, and for a few
     * seconds when a media key is pressed on the remote while the player is not showing.
     */
    var returnToPlayer: Boolean
        get() = prefs.getBoolean(KEY_RETURN, true)
        set(value) = prefs.edit().putBoolean(KEY_RETURN, value).apply()

    /** The remote's Menu button opens the quick settings of an LG TV (picture mode, sound output, ...). */
    var tvMenuButton: Boolean
        get() = prefs.getBoolean(KEY_TV_MENU, false)
        set(value) = prefs.edit().putBoolean(KEY_TV_MENU, value).apply()

    /** The LG TV's address on the local network; empty until found. */
    var lgHost: String
        get() = prefs.getString(KEY_LG_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LG_HOST, value).apply()

    /** What the TV gave this app when its owner accepted the pairing prompt; empty until paired. */
    var lgClientKey: String
        get() = prefs.getString(KEY_LG_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LG_KEY, value).apply()

    /** The TV's own certificate (base64), pinned after the owner confirmed it; empty until then. */
    var lgCertificate: String
        get() = prefs.getString(KEY_LG_CERT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LG_CERT, value).apply()

    /** Forgets the TV: its address, the pairing key and the pinned certificate. */
    fun forgetTv() {
        prefs.edit().remove(KEY_LG_HOST).remove(KEY_LG_KEY).remove(KEY_LG_CERT).apply()
    }

    /**
     * Appear in the iPhone's AirPlay list as a speaker (music only) instead of an Apple TV, see [io.github.besliky.airplaytv.service.Appearance].
     * Switched on as a trial: [speakerTrialUntil] is when it switches itself off again if no music has started by then.
     */
    var speakerMode: Boolean
        get() = prefs.getBoolean(KEY_SPEAKER, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEAKER, value).apply()

    /** Wall-clock milliseconds at which a speaker trial that has not been confirmed ends; 0 when there is no trial running. */
    var speakerTrialUntil: Long
        get() = prefs.getLong(KEY_SPEAKER_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_SPEAKER_UNTIL, value).apply()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val DEFAULT_NAME = "AirPlay TV"
        const val MAX_NAME_LENGTH = 40

        const val KEY_NAME = "device_name"
        const val KEY_LYRICS = "lyrics"
        const val KEY_LYRICS_YTM = "lyrics_youtube_music"
        const val KEY_LYRICS_OFFSET = "lyrics_offset_ms"
        const val KEY_SPEAKER = "appear_as_speaker"
        const val KEY_SPEAKER_UNTIL = "appear_as_speaker_until"
        const val KEY_SLEEP = "sleep_after_minutes"
        const val KEY_RETURN = "return_to_player"
        const val KEY_TV_MENU = "tv_menu_button"
        const val KEY_LG_HOST = "lg_host"
        const val KEY_LG_KEY = "lg_client_key"
        const val KEY_LG_CERT = "lg_certificate"

        /** The choices offered for Sleep After Music; 0 is off. */
        val SLEEP_CHOICES = listOf(0, 2, 5, 10, 20)
        val LYRICS_OFFSET_CHOICES = (-2000..2000 step 250).toList()
        const val KEY_ENABLED = "enabled"
        const val KEY_PIN = "require_pin"
        const val KEY_AUTOSTART = "start_automatically"
        const val KEY_CONNECTION_INFO = "show_connection_info"
        const val KEY_OVERLAY = "performance_overlay"
        const val KEY_VERBOSE = "verbose_logging"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FPS = "frame_rate"
        const val KEY_DECODER = "preferred_decoder"

        /** Settings that require the receiver to be restarted when they change. */
        val RESTART_KEYS = setOf(KEY_NAME, KEY_PIN, KEY_RESOLUTION, KEY_FPS, KEY_DECODER, KEY_SPEAKER)

        /**
         * Normalises a device name: trims, drops control characters and limits the
         * length so that "<id>@<name>" still fits into a 63-byte DNS label.
         */
        fun sanitizeName(raw: String?): String? {
            if (raw == null) return null
            val cleaned = raw
                .map { if (it.isWhitespace()) ' ' else it }
                .filter { !it.isISOControl() }
                .joinToString("")
                .trim()
                .replace(Regex(" +"), " ")
            if (cleaned.isEmpty()) return null
            var name = cleaned.take(MAX_NAME_LENGTH)
            while (name.toByteArray(Charsets.UTF_8).size > 49) {
                name = name.dropLast(1)
            }
            return name.trim().ifEmpty { null }
        }
    }
}
