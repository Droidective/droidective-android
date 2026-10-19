package com.droidective.core.toggles

import com.droidective.core.shell.ShellCommand

/** The Screenshot item: the shell's `screencap`, saved where Photos finds it. */
object Screenshot {
    const val DIR = "/sdcard/Pictures/Screenshots"

    /**
     * Collapses the shade first, since a tile tap leaves it open and it would be in the picture,
     * then captures, then scans: Android 10 and later don't show Photos a file the shell wrote
     * until the media provider is asked to. One line, so the name built from the time reaches every
     * step.
     */
    val command =
        ShellCommand(
            "cmd statusbar collapse; sleep 0.6; mkdir -p $DIR && " +
                "p=$DIR/Screenshot_\$(date +%Y%m%d-%H%M%S)_Droidective.png && " +
                "screencap -p \"\$p\" && " +
                "content call --uri content://media/external/file --method scan_file --arg \"\$p\""
        )
}
