package com.droidective.core.apps

import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/** What the Apps screen can do to an app as the shell user. */
enum class AppAction(val title: String, val destructive: Boolean) {
    ForceStop("Force stop", destructive = false),
    ClearCache("Clear cache", destructive = false),
    ClearData("Clear data", destructive = true),
    Disable("Disable", destructive = true),
    Enable("Enable", destructive = false),
    Uninstall("Uninstall", destructive = true),
}

/**
 * The commands behind [AppAction] and permission changes. Every package and permission name is
 * quoted, though both are also checked, so a malformed one fails here instead of on the phone.
 */
object AppActions {
    private val packageName = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val permissionName = Regex("""^[A-Za-z][A-Za-z0-9_.]*$""")

    fun isPackageName(text: String): Boolean = packageName.matches(text)

    fun isPermissionName(text: String): Boolean = permissionName.matches(text)

    fun command(action: AppAction, pkg: String): ShellCommand {
        require(isPackageName(pkg)) { "\"$pkg\" isn’t a package name" }
        val quoted = shellQuote(pkg)
        return when (action) {
            AppAction.ForceStop -> ShellCommand.of("am", "force-stop", quoted)
            AppAction.ClearCache -> ShellCommand.of("pm", "clear", "--cache-only", quoted)
            AppAction.ClearData -> ShellCommand.of("pm", "clear", quoted)
            AppAction.Disable -> ShellCommand.of("pm", "disable-user", "--user", "0", quoted)
            AppAction.Enable -> ShellCommand.of("pm", "enable", quoted)
            AppAction.Uninstall -> ShellCommand.of("pm", "uninstall", quoted)
        }
    }

    /** `pm grant` or `pm revoke` for a runtime permission. */
    fun permission(pkg: String, permission: String, grant: Boolean): ShellCommand {
        require(isPackageName(pkg)) { "\"$pkg\" isn’t a package name" }
        require(permissionName.matches(permission)) { "\"$permission\" isn’t a permission name" }
        return ShellCommand.of(
            "pm",
            if (grant) "grant" else "revoke",
            shellQuote(pkg),
            shellQuote(permission),
        )
    }
}
