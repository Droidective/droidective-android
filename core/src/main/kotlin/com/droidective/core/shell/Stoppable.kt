package com.droidective.core.shell

/**
 * A streamed command that ends with the app. Ending the stream isn't enough: as root, su's daemon
 * runs the command apart from the app, so a command that prints nothing (`sleep`) or shrugs off a
 * closed pipe (Monkey, a Java program) kept running, and through Shizuku a command's own children
 * outlived the shell that started them.
 *
 * So [wrap] runs the command in a process group of its own (`setsid`) and says the group's id
 * first. The group ends, everything the command started included, when [stop] is sent; when the
 * wrapper is told to end (INT, TERM or HUP, which Shizuku sends when the app stops reading); and
 * within a second of the app's process going, whether it was swiped away, force stopped or killed.
 * Ending is SIGINT first, the Ctrl-C screenrecord saves its file on, then SIGKILL after five
 * seconds for whatever is left. Without `setsid` the command and its direct children end instead.
 */
object Stoppable {
    private const val MARK = "__droidective_pid__"

    /** The line a wrapper script starts with: it says the script's process id. */
    const val SAY_PID = "echo \"$MARK \$\$\""

    /** [command] in a group of its own that ends with the app at [appPid]; `exec` keeps its id. */
    fun wrap(command: ShellCommand, appPid: Int): ShellCommand =
        ShellCommand.of("sh", "-c", shellQuote(guard("exec ${command.line}", appPid)))

    /**
     * The wrapper around [script], run by `sh -c` as the group's first process. Its exit code is
     * the script's, and the watch on [appPid] outlives it while anything the script started runs
     * on.
     */
    fun guard(script: String, appPid: Int): String =
        "s=; command -v setsid >/dev/null && s=setsid\n" +
            "\$s sh -c ${shellQuote(script)} & c=\$!\n" +
            "echo \"$MARK \$c\"\n" +
            ENDING +
            "trap 'trap \"\" INT TERM HUP; end; exit 130' INT TERM HUP\n" +
            "(while alive; do [ -d /proc/$appPid ] || { end; exit; }; sleep 1; done)" +
            " </dev/null >/dev/null 2>&1 &\n" +
            "wait \$c"

    private val pid = Regex("^$MARK (\\d+)$")

    /** The group's id, if this is the line that says it. */
    fun pid(output: String): Int? = pid.find(output.trimEnd('\r'))?.groupValues?.get(1)?.toInt()

    /** Ends the group [pid] said it leads, as the wrapper itself would. */
    fun stop(pid: Int): ShellCommand = ShellCommand("c=$pid\n${ENDING}end")

    /**
     * `alive`: whether anything in the group (or, without `setsid`, the command) still runs. `end`:
     * SIGINT, up to five seconds for it to work, then SIGKILL.
     */
    private const val ENDING =
        "alive() { kill -0 -- -\$c 2>/dev/null || kill -0 \$c 2>/dev/null; }\n" +
            "end() {\n" +
            "  kill -s INT -- -\$c 2>/dev/null || kill -s INT \$c \$(pgrep -P \$c) 2>/dev/null\n" +
            "  i=0; while alive && [ \$i -lt 10 ]; do sleep 0.5; i=\$((i+1)); done\n" +
            "  kill -s KILL -- -\$c 2>/dev/null || kill -s KILL \$c \$(pgrep -P \$c) 2>/dev/null\n" +
            "}\n"
}
