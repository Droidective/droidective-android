package com.droidective.core

import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/**
 * The development permissions Droidective asks a shell for. Only a shell can grant them, which is
 * what the Mac's Phone setup does over adb and what the app does for itself while it has Shell.
 */
object Grants {
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    const val DUMP = "android.permission.DUMP"
    const val READ_DROPBOX_DATA = "android.permission.READ_DROPBOX_DATA"
    const val READ_LOGS = "android.permission.READ_LOGS"

    /**
     * The permission Android files crash reports under for this app: READ_DROPBOX_DATA from Android
     * 15 (API 35), READ_LOGS before it.
     */
    fun crashReports(sdk: Int): String = if (sdk >= ANDROID_15) READ_DROPBOX_DATA else READ_LOGS

    /**
     * Everything to grant on a phone running API [sdk]: WRITE_SECURE_SETTINGS and DUMP, which are
     * the Granted level, and the one Crash Catcher reads crash reports with.
     */
    fun permissions(sdk: Int): List<String> = listOf(WRITE_SECURE_SETTINGS, DUMP, crashReports(sdk))

    /** One `pm grant` per permission, for the app installed as [packageName]. */
    fun commands(packageName: String, sdk: Int): List<ShellCommand> =
        permissions(sdk).map { ShellCommand.of("pm", "grant", shellQuote(packageName), it) }

    /**
     * Usage access, which Android keeps as an app-op rather than a permission. A shell can allow it
     * outright, which saves a trip through a Settings list some phones only reach by name.
     */
    fun usageAccessCommand(packageName: String): ShellCommand =
        ShellCommand.of("appops", "set", shellQuote(packageName), "GET_USAGE_STATS", "allow")

    private const val ANDROID_15 = 35
}
