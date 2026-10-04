package io.github.besliky.airplaytv.service

/** The arithmetic of the two things the stick does on its own for the TV (night brightness, idle turn-off). Pure, so it is tested without a TV or a clock. */
object ScheduleMath {

    /** The OLED brightness to set for the night: [level] (percent), but never above what the TV has ([current]); null when it is at or below it already. */
    fun oledTarget(current: Int, level: Int): Int? = if (current > level) level.coerceIn(0, 100) else null

    /** "setting,value it had,value the night set" as it is kept, or null for anything else. */
    fun parseOled(record: String): Triple<String, Int, Int>? {
        val parts = record.split(',')
        if (parts.size != 3) return null
        val had = parts[1].trim().toIntOrNull() ?: return null
        val set = parts[2].trim().toIntOrNull() ?: return null
        if (parts[0].isBlank()) return null
        return Triple(parts[0].trim(), had, set)
    }

    fun oledRecord(key: String, had: Int, set: Int): String = "$key,$had,$set"

    /** What the morning does to the setting: put it back only when the TV still stands where the night put it (nobody has touched it). */
    fun oledRestore(record: Triple<String, Int, Int>, current: Int?): Int? = if (current != null && current == record.third) record.second else null

    enum class Idle { ACTIVE, WARN, WAITING, TURN_OFF }

    /**
     * Where the idle turn-off stands: [lastActiveMs] is the last key or sound (a time of SystemClock.elapsedRealtime); [warnedAtMs] is when
     * the warning was given (0 when it has not been). The TV is warned once the stick has been idle for [thresholdMs], and turned off when
     * [warnMs] have passed since without a key or a sound.
     */
    fun idleStep(nowMs: Long, lastActiveMs: Long, thresholdMs: Long, warnedAtMs: Long, warnMs: Long): Idle = when {
        nowMs - lastActiveMs < thresholdMs -> Idle.ACTIVE
        warnedAtMs == 0L -> Idle.WARN
        nowMs - warnedAtMs >= warnMs -> Idle.TURN_OFF
        else -> Idle.WAITING
    }
}
