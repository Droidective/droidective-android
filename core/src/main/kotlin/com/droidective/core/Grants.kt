package com.droidective.core

import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/**
 * The development permissions behind the Granted level. Only a shell can grant them, which is what
 * the Mac's Phone setup does over adb and what the app does for itself while it has Shell.
 */
object Grants {
    val permissions = listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP")

    /** One `pm grant` per permission, for the app installed as [packageName]. */
    fun commands(packageName: String): List<ShellCommand> = permissions.map {
        ShellCommand.of("pm", "grant", shellQuote(packageName), it)
    }
}
