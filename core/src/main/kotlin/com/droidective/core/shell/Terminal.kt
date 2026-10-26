package com.droidective.core.shell

/** How a command ended: its exit code, and the folder the shell was in afterwards. */
data class Ending(val exitCode: Int, val cwd: String)

/**
 * One line typed into the terminal, run the way a terminal runs it: in the folder the last one left
 * the shell in, so `cd` sticks, with its exit code reported. Each line is the person's own command,
 * so, like a saved custom command, it runs exactly as typed; only the folder is quoted.
 */
object Terminal {
    /** Where a new terminal starts: the folder every shell can read and write. */
    const val HOME = "/data/local/tmp"

    /** The marker the wrapper prints last; it never reaches the screen. */
    private const val MARK = "__droidective_end__"

    /**
     * The command that runs [line] in [cwd], then says how it ended, as a [Stoppable] group that
     * ends with the app at [appPid], whatever [line] left running in the background included.
     */
    fun wrap(line: String, cwd: String, appPid: Int): ShellCommand =
        ShellCommand.of("sh", "-c", shellQuote(Stoppable.guard(script(line, cwd), appPid)))

    /**
     * Like [wrap], but [line] runs to its end even if the app goes first: an app snapshot stopped
     * halfway would leave a broken copy, or an app's data half put back.
     */
    fun finishing(line: String, cwd: String): ShellCommand =
        ShellCommand.of("sh", "-c", shellQuote("${Stoppable.SAY_PID}\n${script(line, cwd)}"))

    /** A newline before the report keeps a trailing comment or `&` in [line] from swallowing it. */
    private fun script(line: String, cwd: String): String =
        "cd ${shellQuote(cwd)} 2>/dev/null\n$line\necho \"$MARK \$? \$(pwd)\""

    private val ending = Regex("^$MARK (\\d+) (.*)$")

    /** The ending [output] reports, if this is the report line. */
    fun ending(output: String): Ending? =
        ending.find(output.trimEnd('\r'))?.destructured?.let { (code, cwd) ->
            Ending(code.toInt(), cwd)
        }
}

/**
 * The lines typed so far, newest last, walked with up and down like a shell's history. Repeating
 * the last line doesn't add it again, and blank lines aren't kept.
 */
class TerminalHistory(private val limit: Int = DEFAULT_LIMIT) {
    private val lines = ArrayDeque<String>()
    private var cursor = 0

    val all: List<String>
        get() = lines.toList()

    fun add(line: String) {
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && lines.lastOrNull() != trimmed) {
            lines.addLast(trimmed)
            if (lines.size > limit) lines.removeFirst()
        }
        cursor = lines.size
    }

    /** The line before the one shown, staying on the oldest; null with nothing typed yet. */
    fun older(): String? {
        if (lines.isEmpty()) return null
        cursor = (cursor - 1).coerceAtLeast(0)
        return lines[cursor]
    }

    /** The line after the one shown, or empty past the newest, as a shell's prompt goes blank. */
    fun newer(): String {
        cursor = (cursor + 1).coerceAtMost(lines.size)
        return lines.getOrNull(cursor).orEmpty()
    }

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}
