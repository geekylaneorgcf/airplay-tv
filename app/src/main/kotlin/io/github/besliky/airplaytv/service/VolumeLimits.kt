package io.github.besliky.airplaytv.service

/**
 * Limits on how loud the receiver can get: a ceiling, a lower ceiling at night, and a level a session begins at, so a song never
 * starts at the top of the phone's slider. All of it is plain arithmetic, kept apart from the service so it can be tested.
 *
 * A ceiling does not cut the slider off at some position (the top of the slider would then do nothing): the whole slider is
 * scaled into the ceiling, so full is the ceiling and half is half of it.
 */
object VolumeLimits {

    /** [maxPercent] of 100 is no ceiling; [nightMaxPercent] of 0 is no night ceiling; [startPercent] of 0 is no start level. */
    data class Config(
        val maxPercent: Int = 100,
        val nightMaxPercent: Int = 0,
        val nightFromHour: Int = 22,
        val nightToHour: Int = 7,
        val startPercent: Int = 0,
    )

    /** Whether [hour] (0 to 23) is in the night, which may run over midnight; a window with equal ends is never. */
    fun inNight(hour: Int, fromHour: Int, toHour: Int): Boolean = when {
        fromHour == toHour -> false
        fromHour < toHour -> hour in fromHour until toHour
        else -> hour >= fromHour || hour < toHour
    }

    /** The ceiling in percent at [hour]: the night's while it is night, else the day's; never below 10. */
    fun ceilingPercent(config: Config, hour: Int): Int {
        val day = config.maxPercent.coerceIn(MIN_PERCENT, 100)
        val nightSet = config.nightMaxPercent in MIN_PERCENT..99
        return if (nightSet && inNight(hour, config.nightFromHour, config.nightToHour)) minOf(config.nightMaxPercent, day) else day
    }

    /** The slider position the output really uses for the slider position [level] (0 to 1) under [ceilingPercent]. */
    fun apply(level: Float, ceilingPercent: Int): Float = level.coerceIn(0f, 1f) * ceilingPercent.coerceIn(MIN_PERCENT, 100) / 100f

    /** The level a session begins at: the phone's, but not above the start level when there is one. */
    fun startLevel(phoneLevel: Float, config: Config): Float =
        if (config.startPercent in MIN_PERCENT..99) minOf(phoneLevel, config.startPercent / 100f) else phoneLevel

    private const val MIN_PERCENT = 10
}
