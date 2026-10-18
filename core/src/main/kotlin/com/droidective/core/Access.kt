package com.droidective.core

/**
 * The privilege a toggle or feature needs to run in place, from least to most.
 *
 * App is what any installed app has, plus special access granted in Settings. Granted is
 * WRITE_SECURE_SETTINGS and DUMP, which only `pm grant` from a shell can give. Shell is the shell
 * user (uid 2000), reached through Shizuku or root. Root is uid 0.
 */
enum class AccessLevel(val title: String) {
    App("App"),
    Granted("Granted"),
    Shell("Shell"),
    Root("Root"),
}

/** Who runs shell commands for the app. Shizuku started from root runs as uid 0. */
enum class ShellProvider(val title: String, val isRoot: Boolean) {
    Shizuku("Shizuku", isRoot = false),
    ShizukuRoot("Shizuku (root)", isRoot = true),
    Su("Root", isRoot = true),
}

/**
 * What the phone lets the app do right now.
 *
 * @property granted whether WRITE_SECURE_SETTINGS is held, so Global and Secure settings can be
 *   written through the Settings API.
 * @property shell the shell provider that is running and has granted the app access, if any.
 */
data class Access(val granted: Boolean, val shell: ShellProvider?) {
    /**
     * Whether something that needs [level] can run in place. A shell can do anything Granted can.
     */
    fun allows(level: AccessLevel): Boolean =
        when (level) {
            AccessLevel.App -> true
            AccessLevel.Granted -> granted || shell != null
            AccessLevel.Shell -> shell != null
            AccessLevel.Root -> shell?.isRoot == true
        }

    companion object {
        val Nothing = Access(granted = false, shell = null)
    }
}
