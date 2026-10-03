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
        get() = sanitizeName(prefs.getString(KEY_NAME, null)) ?: (if (BuildConfig.DEBUG) "$DEFAULT_NAME Dev" else DEFAULT_NAME)
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


    /** Wake the TV over the network (Wake-on-LAN) when a sender connects while it is off. Needs the TV paired; the TV's own setting "Turn on via Wi-Fi" must be on. */
    var tvWake: Boolean
        get() = prefs.getBoolean(KEY_TV_WAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_TV_WAKE, value).apply()

    /** The TV's hardware addresses (wired and wireless), comma separated, learned from the TV when it was paired. */
    var lgMacs: String
        get() = prefs.getString(KEY_LG_MACS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LG_MACS, value).apply()

    /** What the TV shows when this stick is its input (`com.webos.app.hdmi1`), learned while the owner was looking at this stick's own screen. */
    var lgInput: String
        get() = prefs.getString(KEY_LG_INPUT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LG_INPUT, value).apply()

    /** While only music plays, turn the TV's screen off (the sound goes on) and back on at the next key or when the music ends. */
    var musicMode: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_MUSIC_MODE, value).apply()

    /** Pause the phone's music when the TV is switched to another input or off. */
    var smartPause: Boolean
        get() = prefs.getBoolean(KEY_SMART_PAUSE, true)
        set(value) = prefs.edit().putBoolean(KEY_SMART_PAUSE, value).apply()

    /** Move the TV's own volume with the phone's slider and the remote's volume keys, when the TV lets it be moved. */
    var tvVolume: Boolean
        get() = prefs.getBoolean(KEY_TV_VOLUME, true)
        set(value) = prefs.edit().putBoolean(KEY_TV_VOLUME, value).apply()

    /**
     * The TV's volume that a session of this receiver lowered and has not given back yet, as "the volume the receiver put it at,the volume
     * it had"; empty when nothing is owed. A session that ends in the usual way gives the volume back and empties it. One that does not
     * (the app was stopped, the TV did not answer) would leave the TV low for good, and the next session would take that low volume for
     * the owner's own; see [io.github.besliky.airplaytv.service.TvVolume.leftBehind].
     */
    var tvLeftBehind: String
        get() = prefs.getString(KEY_TV_LEFT_BEHIND, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TV_LEFT_BEHIND, value).apply()

    /** Volume limits, see [io.github.besliky.airplaytv.service.VolumeLimits]. */
    var maxVolumePercent: Int
        get() = prefs.getInt(KEY_VOLUME_MAX, 100).let { if (it in MAX_VOLUME_CHOICES) it else 100 }
        set(value) = prefs.edit().putInt(KEY_VOLUME_MAX, value).apply()

    var nightVolumePercent: Int
        get() = prefs.getInt(KEY_VOLUME_NIGHT, 0).let { if (it in NIGHT_VOLUME_CHOICES) it else 0 }
        set(value) = prefs.edit().putInt(KEY_VOLUME_NIGHT, value).apply()

    var nightFromHour: Int
        get() = prefs.getInt(KEY_NIGHT_FROM, 22).let { if (it in NIGHT_FROM_CHOICES) it else 22 }
        set(value) = prefs.edit().putInt(KEY_NIGHT_FROM, value).apply()

    var nightToHour: Int
        get() = prefs.getInt(KEY_NIGHT_TO, 7).let { if (it in NIGHT_TO_CHOICES) it else 7 }
        set(value) = prefs.edit().putInt(KEY_NIGHT_TO, value).apply()

    var startVolumePercent: Int
        get() = prefs.getInt(KEY_VOLUME_START, 0).let { if (it in START_VOLUME_CHOICES) it else 0 }
        set(value) = prefs.edit().putInt(KEY_VOLUME_START, value).apply()

    fun volumeLimits() = io.github.besliky.airplaytv.service.VolumeLimits.Config(
        maxPercent = maxVolumePercent,
        nightMaxPercent = nightVolumePercent,
        nightFromHour = nightFromHour,
        nightToHour = nightToHour,
        startPercent = startVolumePercent,
    )

    /** What happens when a second phone starts playing while one is: [TAKEOVER_REPLACE], [TAKEOVER_KEEP] or [TAKEOVER_ASK]. */
    var takeover: Int
        get() = prefs.getInt(KEY_TAKEOVER, TAKEOVER_REPLACE).let { if (it in TAKEOVER_CHOICES) it else TAKEOVER_REPLACE }
        set(value) = prefs.edit().putInt(KEY_TAKEOVER, value).apply()

    /** Sound: bass and treble in steps of 3 dB (-3 to 3), a loudness boost (0 to 3) and a night mode that evens out loud and quiet. */
    var bass: Int
        get() = prefs.getInt(KEY_BASS, 0).coerceIn(-3, 3)
        set(value) = prefs.edit().putInt(KEY_BASS, value.coerceIn(-3, 3)).apply()

    var treble: Int
        get() = prefs.getInt(KEY_TREBLE, 0).coerceIn(-3, 3)
        set(value) = prefs.edit().putInt(KEY_TREBLE, value.coerceIn(-3, 3)).apply()

    var loudness: Int
        get() = prefs.getInt(KEY_LOUDNESS, 0).coerceIn(0, 3)
        set(value) = prefs.edit().putInt(KEY_LOUDNESS, value.coerceIn(0, 3)).apply()

    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT_MODE, value).apply()

    /** A calm visualizer on the Now Playing screen. */
    var visualizer: Boolean
        get() = prefs.getBoolean(KEY_VISUALIZER, true)
        set(value) = prefs.edit().putBoolean(KEY_VISUALIZER, value).apply()

    /**
     * Offer AirPlay video: the cast button in apps such as Safari and YouTube sends the address of the video and this receiver plays it
     * itself. Mirroring and music do not need it. A change restarts the receiver, which announces itself again.
     */
    var airplayVideo: Boolean
        get() = prefs.getBoolean(KEY_VIDEO, true)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO, value).apply()

    /** The version whose "What's new" has been shown. */
    var seenVersion: String
        get() = prefs.getString(KEY_SEEN_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SEEN_VERSION, value).apply()

    /** Forgets the TV: its address, the pairing key, the pinned certificate and what was learned about it. */
    fun forgetTv() {
        prefs.edit().remove(KEY_LG_HOST).remove(KEY_LG_KEY).remove(KEY_LG_CERT).remove(KEY_LG_MACS).remove(KEY_LG_INPUT).apply()
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

    /**
     * AirPlay 2 for music (see docs/AIRPLAY2.md): [AIRPLAY2_OFF], [AIRPLAY2_ON], [AIRPLAY2_FULL] (the same, with the timing and
     * buffered-audio feature bits set, to see what a phone does with them) or [AIRPLAY2_NTP] (the buffered-audio bit only, no PTP
     * bit, so that a phone uses the old timing). Not for mirroring, photos or video, which stay AirPlay 1.
     * Switched on as a trial: [airplay2TrialUntil] is when it switches itself off again if no phone has started a session by then.
     */
    var airplay2: Int
        get() = prefs.getInt(KEY_AIRPLAY2, AIRPLAY2_OFF).coerceIn(AIRPLAY2_OFF, AIRPLAY2_PTP_ONLY)
        set(value) = prefs.edit().putInt(KEY_AIRPLAY2, value).apply()

    /**
     * An experiment for the debug build, set with `run-as` and never from a screen: addresses (comma separated) that the AirPlay 2 mode
     * with the PTP bit names as its clock peer instead of this device, for a host that listens on UDP 319 and 320 where an app cannot.
     */
    var airplay2TimingPeer: String
        get() = prefs.getString(KEY_AIRPLAY2_PEER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AIRPLAY2_PEER, value).apply()

    /** Wall-clock milliseconds at which an AirPlay 2 trial that no session has confirmed ends; 0 when there is no trial running. */
    var airplay2TrialUntil: Long
        get() = prefs.getLong(KEY_AIRPLAY2_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_AIRPLAY2_UNTIL, value).apply()

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
        const val KEY_AIRPLAY2 = "airplay2_mode"
        const val KEY_AIRPLAY2_UNTIL = "airplay2_until"
        const val KEY_AIRPLAY2_PEER = "airplay2_timing_peer"
        const val AIRPLAY2_OFF = 0
        const val AIRPLAY2_ON = 1
        const val AIRPLAY2_FULL = 2
        const val AIRPLAY2_NTP = 3
        const val AIRPLAY2_PTP_ONLY = 4 // the PTP bit without the buffered-audio bit: an experiment, set with run-as
        const val KEY_SLEEP = "sleep_after_minutes"
        const val KEY_RETURN = "return_to_player"
        const val KEY_TV_MENU = "tv_menu_button"
        const val KEY_LG_HOST = "lg_host"
        const val KEY_LG_KEY = "lg_client_key"
        const val KEY_LG_CERT = "lg_certificate"
        const val KEY_LG_MACS = "lg_macs"
        const val KEY_LG_INPUT = "lg_input"
        const val KEY_TV_WAKE = "tv_wake"
        const val KEY_MUSIC_MODE = "music_mode"
        const val KEY_SMART_PAUSE = "smart_pause"
        const val KEY_TV_VOLUME = "tv_volume"
        const val KEY_TV_LEFT_BEHIND = "tv_left_behind"
        const val KEY_VOLUME_MAX = "volume_max"
        const val KEY_VOLUME_NIGHT = "volume_night"
        const val KEY_NIGHT_FROM = "night_from"
        const val KEY_NIGHT_TO = "night_to"
        const val KEY_VOLUME_START = "volume_start"
        const val KEY_TAKEOVER = "takeover"
        const val KEY_BASS = "bass"
        const val KEY_TREBLE = "treble"
        const val KEY_LOUDNESS = "loudness"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_VISUALIZER = "visualizer"
        const val KEY_SEEN_VERSION = "seen_version"
        const val KEY_VIDEO = "airplay_video"

        /** What each limit offers, in the order the setting cycles through (the first is "no limit"). */
        val MAX_VOLUME_CHOICES = listOf(100, 90, 80, 70, 60, 50, 40)
        val NIGHT_VOLUME_CHOICES = listOf(0, 50, 40, 30, 20)
        val NIGHT_FROM_CHOICES = listOf(20, 21, 22, 23, 0)
        val NIGHT_TO_CHOICES = listOf(5, 6, 7, 8, 9)
        val START_VOLUME_CHOICES = listOf(0, 50, 40, 30, 20)

        const val TAKEOVER_REPLACE = 0
        const val TAKEOVER_KEEP = 1
        const val TAKEOVER_ASK = 2
        val TAKEOVER_CHOICES = listOf(TAKEOVER_REPLACE, TAKEOVER_KEEP, TAKEOVER_ASK)

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
        val RESTART_KEYS = setOf(KEY_NAME, KEY_PIN, KEY_RESOLUTION, KEY_FPS, KEY_DECODER, KEY_SPEAKER, KEY_VIDEO, KEY_AIRPLAY2)

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
