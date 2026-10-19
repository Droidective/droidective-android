package com.droidective.core.device

import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/** One line `bugreportz -p` printed, read. */
sealed interface BugReportLine {
    /** Where the report is being written. */
    data class Begin(val path: String) : BugReportLine

    data class Progress(val done: Int, val of: Int) : BugReportLine

    /** The finished zip, in the shell user's own folder. */
    data class Done(val path: String) : BugReportLine

    data class Failed(val reason: String) : BugReportLine
}

/**
 * The Mac's Bug Report on the phone: `bugreportz`, the zipped report Android Studio and the Play
 * Console ask for, written by the shell user and copied to Downloads, where any app can share it.
 */
object BugReport {
    /** `-p` prints progress while it gathers, which takes a minute or more. */
    val command: ShellCommand = ShellCommand.of("bugreportz", "-p")

    /** Reads one line of `bugreportz -p`; null for anything else it says. */
    fun parse(line: String): BugReportLine? {
        val text = line.trim()
        val (kind, value) = text.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        return when (kind) {
            "BEGIN" -> BugReportLine.Begin(value)
            "OK" -> BugReportLine.Done(value)
            "FAIL" -> BugReportLine.Failed(value.ifBlank { "bugreportz gave no reason" })
            "PROGRESS" -> progress(value)
            else -> null
        }
    }

    /** `PROGRESS:20/5000`: done of a total in units of its own (5000 on Android 16, not 100). */
    private fun progress(value: String): BugReportLine.Progress? {
        val (done, of) =
            value
                .split('/')
                .mapNotNull { it.trim().toIntOrNull() }
                .takeIf { it.size == 2 && it[1] > 0 } ?: return null
        return BugReportLine.Progress(done.coerceIn(0, of), of)
    }

    /** The zip's own name, from its path. */
    fun fileName(path: String): String = path.substringAfterLast('/')

    /**
     * Copies the finished zip into [downloads], keeping its name: the shell user can write
     * Downloads and every app can read it.
     */
    fun copyToDownloads(path: String, downloads: String): ShellCommand {
        require(path.endsWith(".zip") && ".." !in path) { "\"$path\" isn’t a bug report" }
        return ShellCommand.of("cp", shellQuote(path), shellQuote("$downloads/${fileName(path)}"))
    }
}
