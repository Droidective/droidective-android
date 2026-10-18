package com.droidective.core.apps

import com.droidective.core.commands.DeepLinkRepository
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/**
 * `am start` lines for opening something from the background. Android blocks an app that isn't on
 * screen from starting activities, but not the shell, so an automation with Shell opens apps, links
 * and Settings screens this way. Another app's shortcut never goes through here: the shell could
 * reach activities that app never meant to share.
 */
object ShellStarts {
    private val className = Regex("""^[A-Za-z_][A-Za-z0-9_$]*(\.[A-Za-z_][A-Za-z0-9_$]*)*$""")
    private val actionName = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

    /** An app's launch activity, as `am start -n`. */
    fun activity(packageName: String, activityName: String): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        require(className.matches(activityName)) { "\"$activityName\" isn’t a class name" }
        return ShellCommand.of(
            "am",
            "start",
            "-n",
            shellQuote("$packageName/$activityName"),
        )
    }

    fun link(uri: String): ShellCommand {
        require(DeepLinkRepository.isLink(uri)) { "\"$uri\" isn’t a link" }
        return ShellCommand.of(
            "am",
            "start",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            shellQuote(uri.trim()),
        )
    }

    fun settingsScreen(action: String): ShellCommand {
        require(actionName.matches(action)) { "\"$action\" isn’t an intent action" }
        return ShellCommand.of("am", "start", "-a", shellQuote(action))
    }
}
