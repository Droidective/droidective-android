package com.droidective.core.apps

import com.droidective.core.shell.ShellCommand

/** Which activity is on screen: the Mac's Current Activity. */
object CurrentActivity {
    /**
     * What the shell reads it from; [com.droidective.core.shell.Parse.resumedActivity] parses it.
     */
    val command: ShellCommand = ShellCommand.of("dumpsys", "activity", "activities")

    /**
     * `package/.Activity` for an activity inside its own package, else `package/full.Class`: the
     * short form `ComponentName.flattenToShortString` and `am start -n` use.
     */
    fun component(packageName: String, className: String): String =
        if (className.startsWith("$packageName.")) {
            "$packageName/${className.removePrefix(packageName)}"
        } else {
            "$packageName/$className"
        }
}
