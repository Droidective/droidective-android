package com.droidective.core.apps

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/** One app-op and its mode, with when the app last used it or was refused, as appops says. */
data class AppOp(
    val name: String,
    val mode: String,
    val lastUsed: String?,
    val lastRefused: String?,
)

/** An app-op worth showing on its own, and what each of its modes means for the app. */
data class KnownOp(val name: String, val title: String, val meaning: String)

/**
 * App-ops: the access Android tracks per app beyond runtime permissions, read and set as the shell
 * with `appops`. Some phones refuse `appops set` to the shell user (OxygenOS says it lacks
 * MANAGE_APP_OPS_MODES); root can still set them.
 */
object AppOps {
    /** The modes `appops set` takes. `foreground` allows it only while the app is in front. */
    val modes = listOf("allow", "ignore", "deny", "default", "foreground")

    /** The special accesses a developer tests, most asked about first. */
    val known =
        listOf(
            KnownOp(
                "RUN_ANY_IN_BACKGROUND",
                "Run in the background",
                "ignore is Battery ▸ Restricted: no jobs, alarms or services in the background.",
            ),
            KnownOp(
                "SYSTEM_ALERT_WINDOW",
                "Display over other apps",
                "allow lets it draw over other apps and start screens from the background.",
            ),
            KnownOp(
                "GET_USAGE_STATS",
                "Usage access",
                "allow lets it see which apps were used, and when.",
            ),
            KnownOp(
                "SCHEDULE_EXACT_ALARM",
                "Alarms and reminders",
                "allow lets it set exact alarms that fire on time, even in Doze.",
            ),
            KnownOp(
                "REQUEST_INSTALL_PACKAGES",
                "Install unknown apps",
                "allow lets it install other apps.",
            ),
            KnownOp(
                "MANAGE_EXTERNAL_STORAGE",
                "All files access",
                "allow lets it read and write all of shared storage.",
            ),
            KnownOp(
                "WRITE_SETTINGS",
                "Modify system settings",
                "allow lets it change settings like brightness and the screen timeout.",
            ),
            KnownOp(
                "PICTURE_IN_PICTURE",
                "Picture-in-picture",
                "ignore stops it shrinking into a floating window.",
            ),
            KnownOp(
                "POST_NOTIFICATION",
                "Notifications",
                "Android keeps this in step with the notification permission.",
            ),
        )

    private val opName = Regex("""^[A-Z][A-Z0-9_]*$""")
    private val line = Regex("""^(?:Uid mode: )?([A-Z][A-Z0-9_]*): ([a-z]+)(.*)$""")

    fun get(packageName: String): ShellCommand = ShellCommand("appops get ${quoted(packageName)}")

    fun set(packageName: String, op: String, mode: String): ShellCommand {
        require(opName.matches(op)) { "\"$op\" isn’t an app-op" }
        require(mode in modes) { "\"$mode\" isn’t a mode appops takes" }
        return ShellCommand("appops set ${quoted(packageName)} $op $mode")
    }

    /** Every app-op back to Android's default for the app. */
    fun reset(packageName: String): ShellCommand =
        ShellCommand("appops reset ${quoted(packageName)}")

    /**
     * The ops [output] lists, one per line, the uid's and the package's alike. A name listed twice
     * keeps its first line, which is the uid's: that's the one Android applies.
     */
    fun parse(output: String): List<AppOp> =
        Parse.lines(output)
            .mapNotNull { text -> line.find(text.trim())?.destructured }
            .map { (name, mode, rest) ->
                AppOp(name, mode, after(rest, "time="), after(rest, "rejectTime="))
            }
            .distinctBy { it.name }

    private fun after(rest: String, field: String): String? =
        rest
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith(field) }
            ?.removePrefix(field)
            ?.removeSuffix(" ago")

    private fun quoted(packageName: String): String {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return shellQuote(packageName)
    }
}
