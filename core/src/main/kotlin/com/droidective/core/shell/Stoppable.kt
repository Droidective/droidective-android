package com.droidective.core.shell

/**
 * A streamed command that can be stopped where it runs. Ending the stream isn't enough as root:
 * su's daemon runs the command apart from the su that started it, so a command that prints nothing
 * (`sleep`), or one that shrugs off a closed pipe (Monkey, a Java program), kept running after the
 * stream closed. The wrapper says its process id first, and [stop] ends it and its children.
 */
object Stoppable {
    private const val MARK = "__droidective_pid__"

    /** The line a wrapper script starts with: it says the script's process id. */
    const val SAY_PID = "echo \"$MARK \$\$\""

    /** [command], saying its process id first; `exec` keeps that id the command's own. */
    fun wrap(command: ShellCommand): ShellCommand =
        ShellCommand.of("sh", "-c", shellQuote("$SAY_PID\nexec ${command.line}"))

    private val pid = Regex("^$MARK (\\d+)$")

    /** The wrapper's process id, if this is the line that says it. */
    fun pid(output: String): Int? = pid.find(output.trimEnd('\r'))?.groupValues?.get(1)?.toInt()

    /**
     * Ends the process at [pid] and its children. The children come from `pgrep -P`: toybox's
     * `pkill -TERM -P` matched nothing with a child running.
     */
    fun stop(pid: Int): ShellCommand = ShellCommand("kill -TERM \$(pgrep -P $pid) $pid")
}
