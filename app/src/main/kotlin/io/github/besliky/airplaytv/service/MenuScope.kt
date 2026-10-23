package io.github.besliky.airplaytv.service

/**
 * Where the Menu button is left alone. In the system's own settings screens the button has a job of its own (on a Bluetooth device it
 * offers to forget it, for instance), and taking it there to open the LG TV's menu gets in the way. Pure, so it is tested without a screen.
 */
object MenuScope {

    private val SYSTEM_SCREENS = setOf(
        "com.android.settings", "com.amazon.tv.settings.v2", "com.amazon.tv.inputpreference.service",
        "com.android.packageinstaller", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
    )

    /** Whether a press of Menu goes to the app in front, [packageName] being the package of the window that came to the front last. */
    fun leaveToApp(packageName: String?): Boolean = packageName != null && packageName in SYSTEM_SCREENS
}
