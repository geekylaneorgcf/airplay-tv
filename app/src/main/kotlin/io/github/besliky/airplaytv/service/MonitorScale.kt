package io.github.besliky.airplaytv.service

/** The arithmetic of monitor mode, kept free of Android so it can be tested. */
object MonitorScale {

    const val MAX_BRIGHTNESS = 10
    const val MAX_WARMTH = 5

    /** Silence: what mute and volume 0 apply. */
    const val FLOOR_DB = -60f
    private const val DB_PER_STEP = 3f

    /** The darkest step stops short of black so the picture can always be seen and brought back. */
    private const val MAX_DIM = 0.85f
    private const val MAX_WARM = 0.30f

    /** The gain for system volume [index] of [max]: 0 dB at the top, 3 dB a step down, silence at 0 or muted. */
    fun gainDb(index: Int, max: Int, muted: Boolean): Float = when {
        muted || index <= 0 -> FLOOR_DB
        index >= max -> 0f
        else -> maxOf(FLOOR_DB, -(max - index) * DB_PER_STEP)
    }

    /** Alpha (0 to 255) of the black layer for brightness [level]; 0 at full brightness. */
    fun dimAlpha(level: Int): Int =
        Math.round((MAX_BRIGHTNESS - level.coerceIn(0, MAX_BRIGHTNESS)) / MAX_BRIGHTNESS.toFloat() * MAX_DIM * 255)

    /** Alpha (0 to 255) of the amber layer for warmth [level]; 0 when off. */
    fun warmAlpha(level: Int): Int = Math.round(level.coerceIn(0, MAX_WARMTH) / MAX_WARMTH.toFloat() * MAX_WARM * 255)

    // flinger track row: "<T> <Name> <Active> <Client pid> <Session> <S> 0x<flags> ..."
    private val TRACK = Regex("""^\s+(?:\S+\s+)?(?:\d+|-)\s+(?:yes|no)\s+\d+\s+(\d+)\s+[A-Z]\s+0x""")

    // "Global session refs" row: "<session> <pid> <count>"
    private val REF = Regex("""^\s+(\d+)\s+(\d+)\s+\d+\s*$""")

    /**
     * The audio sessions to turn down, from `dumpsys media.audio_flinger`: every track on a mixer (this app's own included, so
     * that AirPlay's sound follows the volume too), and the sessions another app holds before any track plays (attaching early
     * covers the start of playback). This app's own references are skipped: its effects hold them. Null when the dump was refused.
     */
    fun sessions(lines: Sequence<String>, ownPid: Int): Set<Int>? {
        val found = LinkedHashSet<Int>()
        var refs = false
        for (line in lines) {
            if (line.contains("Permission Denial")) return null
            if (line.startsWith("Global session refs")) {
                refs = true
                continue
            }
            if (refs) {
                val ref = REF.find(line)
                if (ref != null) {
                    val session = ref.groupValues[1].toInt()
                    if (session > 0 && ref.groupValues[2].toInt() != ownPid) found.add(session)
                    continue
                }
                if (!line.contains("session")) refs = false
            }
            TRACK.find(line)?.let { found.add(it.groupValues[1].toInt()) }
        }
        return found
    }
}
