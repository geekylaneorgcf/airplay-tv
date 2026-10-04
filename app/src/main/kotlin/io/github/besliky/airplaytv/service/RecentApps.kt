package io.github.besliky.airplaytv.service

/**
 * The list of the apps that were in front last, for the Hold Home switcher: most recent first, no app twice, a few at most. Pure, so it is
 * tested without a screen. The list is kept as package names separated by spaces.
 */
object RecentApps {

    const val MAX = 5

    /** Packages that are never an app to go back to: the home screens, the system's own windows and this app's overlays. */
    private val NEVER = setOf(
        "android", "com.android.systemui", "com.amazon.tv.launcher", "io.github.geekylaneorgcf.tvhome",
        "com.android.settings", "com.amazon.tv.settings.v2", "com.amazon.tv.inputpreference.service", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.amazon.device.sale.service",
    )

    /** Whether windows of [packageName] count: it is not one of the system's, nor [own] (this app: its overlays and screens). */
    fun counts(packageName: String?, own: String): Boolean =
        !packageName.isNullOrEmpty() && packageName != own && packageName !in NEVER

    /** [list] with [packageName] put first (moved there when it was in the list), cut to [MAX]. */
    fun add(list: List<String>, packageName: String): List<String> =
        (listOf(packageName) + list.filter { it != packageName }).take(MAX)

    fun parse(stored: String): List<String> = stored.split(' ').filter { it.isNotBlank() }.distinct().take(MAX)

    fun encode(list: List<String>): String = list.joinToString(" ")

    /** What the switcher offers: [list] without [current] (the app in front is not somewhere to go back to), and only what [launchable] says can be started. */
    fun choices(list: List<String>, current: String?, launchable: (String) -> Boolean): List<String> =
        list.filter { it != current && launchable(it) }.take(MAX)
}
