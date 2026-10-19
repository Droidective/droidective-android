package com.droidective.core.logs

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/** Logcat's priorities, lowest first; [letter] is how threadtime writes them. */
enum class LogLevel(val letter: Char, val title: String) {
    Verbose('V', "Verbose"),
    Debug('D', "Debug"),
    Info('I', "Info"),
    Warn('W', "Warning"),
    Error('E', "Error"),
    Fatal('F', "Fatal"),
    Silent('S', "Silent");

    companion object {
        fun of(letter: Char): LogLevel? = entries.firstOrNull { it.letter == letter }
    }
}

/**
 * One logcat line. A line that isn't threadtime (a `--------- beginning of main` banner, a wrapped
 * continuation) keeps [raw] as its [message] and nulls for the rest.
 */
data class LogLine(
    val time: String,
    val pid: Int?,
    val tid: Int?,
    val level: LogLevel?,
    val tag: String,
    val message: String,
    val raw: String,
)

/** What to ask logcat for. */
data class LogcatFilters(
    /** `-T`: start from the last N lines, then follow. */
    val tail: Int = DEFAULT_TAIL,
    /** `*:<level>`: drop everything quieter. */
    val minimum: LogLevel? = null,
    /** `--pid`: one process. */
    val pid: Int? = null,
) {
    companion object {
        const val DEFAULT_TAIL = 300
    }
}

/** Logcat through the shell: the command, the threadtime parser, and finding an app's pid. */
object Logcat {
    private val threadtime =
        Regex(
            """^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+(.*?):\s?(.*)$"""
        )

    /**
     * `logcat -v threadtime -T <n>`, following. The level filter is quoted: `*` is a glob to the
     * shell the command runs in.
     */
    fun command(filters: LogcatFilters): ShellCommand {
        val words = mutableListOf("logcat", "-v", "threadtime", "-T", filters.tail.toString())
        filters.pid?.let { words += listOf("--pid", it.toString()) }
        filters.minimum?.let { words += shellQuote("*:${it.letter}") }
        return ShellCommand.of(*words.toTypedArray())
    }

    /** Reads one threadtime line; logcat pads short tags with spaces, which are dropped. */
    fun parse(line: String): LogLine {
        val raw = line.trimEnd('\r')
        val match =
            threadtime.matchEntire(raw) ?: return LogLine("", null, null, null, "", raw, raw)
        val (time, pid, tid, level, tag, message) = match.destructured
        return LogLine(
            time = time,
            pid = pid.toIntOrNull(),
            tid = tid.toIntOrNull(),
            level = LogLevel.of(level.single()),
            tag = tag.trim(),
            message = message,
            raw = raw,
        )
    }

    /** `pidof <package>`: every process the package runs, separated by spaces. */
    fun pidCommand(packageName: String): ShellCommand =
        ShellCommand.of("pidof", shellQuote(packageName))

    /** The first pid `pidof` printed, or null when the app isn't running. */
    fun parsePid(output: String): Int? =
        Parse.lines(output).firstOrNull()?.trim()?.split(' ')?.firstOrNull()?.toIntOrNull()
}

/** The lines a log view keeps: the newest [capacity], the oldest dropped first. */
class LogBuffer(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lines = ArrayDeque<LogLine>()

    val size: Int
        get() = lines.size

    fun add(batch: List<LogLine>) {
        lines.addAll(batch)
        repeat(lines.size - capacity) { lines.removeFirst() }
    }

    fun snapshot(): List<LogLine> = lines.toList()

    fun clear() = lines.clear()

    companion object {
        const val DEFAULT_CAPACITY = 5_000

        /** Whether [line] belongs in a view searching for [query]; a blank query keeps all. */
        fun matches(line: LogLine, query: String): Boolean =
            query.isBlank() || line.raw.contains(query.trim(), ignoreCase = true)
    }
}
