package io.github.besliky.airplaytv.service

import android.content.Context
import android.provider.Settings as SystemSettings

/**
 * The commands that switch one of this app's accessibility services on. Android keeps all enabled services in
 * one colon-separated setting, so the command has to carry the ones that are already on, or it would
 * switch them off.
 */
object AccessibilitySetup {

    /**
     * A service's name as `enabled_accessibility_services` takes it, in the short form Android accepts
     * (`package/.service.Name`), which is half the length of the full one to type.
     */
    fun component(packageName: String, className: String): String =
        if (className.startsWith("$packageName.")) "$packageName/${className.removePrefix(packageName)}" else "$packageName/$className"

    /** The two `adb` commands that switch [component] on and leave whatever else is on as it is. */
    fun commands(context: Context, component: String): List<String> {
        val current = SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return listOf(
            "adb shell settings put secure enabled_accessibility_services ${withService(current, component)}",
            "adb shell settings put secure accessibility_enabled 1",
        )
    }

    /** The value of `enabled_accessibility_services` with [component] added to [current] (not twice, and in either spelling). */
    internal fun withService(current: String?, component: String): String {
        val entries = current.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.any { full(it) == full(component) }) return entries.joinToString(":")
        return (entries + component).joinToString(":")
    }

    /** `package/.Class` and `package/package.Class` are the same service; this spells both the second way. */
    private fun full(entry: String): String {
        val slash = entry.indexOf('/')
        if (slash < 0) return entry
        val pkg = entry.substring(0, slash)
        val cls = entry.substring(slash + 1)
        return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
    }
}
