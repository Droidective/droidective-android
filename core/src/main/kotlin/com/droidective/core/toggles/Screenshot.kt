package com.droidective.core.toggles

import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/** The Screenshot items: the shell's `screencap`, saved where Photos finds it. */
object Screenshot {
    // The shell user's path, written into a shell line: Environment would give this app's view.
    @Suppress("SdCardPath") const val DIR = "/sdcard/Pictures/Screenshots"

    /**
     * Captures, then scans: Android 10 and later don't show Photos a file the shell wrote until the
     * media provider is asked to. The name built from the time reaches both steps.
     */
    private const val CAPTURE =
        "mkdir -p $DIR && " +
            "p=$DIR/Screenshot_\$(date +%Y%m%d-%H%M%S)_Droidective.png && " +
            "screencap -p \"\$p\" && " +
            "content call --uri content://media/external/file --method scan_file --arg \"\$p\""

    /**
     * Collapses the shade first, since a tile tap leaves it open and it would be in the picture.
     * One line, so every step shares the shell's variables.
     */
    val command = ShellCommand("cmd statusbar collapse; sleep 0.6; $CAPTURE")

    /**
     * The same, under demo mode's tidy status bar: a full battery and signal, 9:41 and no
     * notification icons, the way store listings show an app. Demo mode ends afterwards whether or
     * not the capture worked, `sysui_demo_allowed` goes back to what it was, and the line exits
     * with the capture's own status.
     */
    val clean: ShellCommand = run {
        val allowed = DemoMode.ALLOWED_SETTING
        ShellCommand(
            listOf(
                    "cmd statusbar collapse",
                    "a=\$(settings get global $allowed)",
                    "settings put global $allowed 1",
                    DemoMode.enter.joinToString("; ", transform = ::broadcast),
                    "sleep 0.6",
                    CAPTURE,
                    "s=\$?",
                    broadcast(DemoMode.exit),
                    "if [ \"\$a\" = null ]; then settings delete global $allowed; " +
                        "else settings put global $allowed \"\$a\"; fi",
                    "exit \$s",
                )
                .joinToString("; ")
        )
    }

    /**
     * The screen saved as [path], readable by this app, which a file the shell writes otherwise
     * isn't: the UI Inspector's picture beside its dump, and one to mark up.
     */
    fun toFile(path: String): ShellCommand {
        require(path.endsWith(".png") && ".." !in path) { "\"$path\" isn’t a screenshot's path" }
        val quotedPath = shellQuote(path)
        return ShellCommand("screencap -p $quotedPath && chmod 644 $quotedPath")
    }

    /** One demo-mode broadcast, as the shell sends it. */
    private fun broadcast(extras: Map<String, String>): String =
        "am broadcast -a ${DemoMode.ACTION}" +
            extras.entries.joinToString("") { (key, value) -> " -e $key ${shellQuote(value)}" }
}
