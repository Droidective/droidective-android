package com.droidective.core.shell

import kotlinx.coroutines.flow.Flow

/**
 * Wraps [value] in single quotes for the device's `sh`, so it reaches the command as one word
 * whatever it contains. This is the security boundary for every value a person types.
 */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * One command line for the device's `sh`.
 *
 * Build it from fixed words and [shellQuote]d values; [line] is run exactly as written.
 */
@JvmInline
value class ShellCommand(val line: String) {
    override fun toString(): String = line

    companion object {
        /**
         * Joins [words] with single spaces. Quote any value a person supplied before passing it.
         */
        fun of(vararg words: String): ShellCommand = ShellCommand(words.joinToString(" "))
    }
}

/** What a command printed and how it exited. */
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded: Boolean
        get() = exitCode == 0
}

/** Thrown when the shell provider is gone, for example Shizuku stopped or su was revoked. */
class ShellUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Runs commands, as the app, the shell user or root depending on the implementation. The
 * implementations live in the platform module; tests use FakeShell.
 */
interface Shell {
    /**
     * Runs [command] through `sh -c` and waits for it.
     *
     * @throws ShellUnavailableException when the provider is no longer reachable.
     */
    suspend fun run(command: ShellCommand): ShellResult

    /**
     * Starts [command] through `sh -c` and emits each line it prints, stdout and stderr together,
     * until it exits. Collecting stops it: cancelling the collector ends the command. [command] is
     * one long-running command, such as logcat: it replaces the shell (`exec`), so stopping it
     * can't leave it running under a stopped `sh`.
     *
     * @throws ShellUnavailableException when the provider is no longer reachable.
     */
    fun stream(command: ShellCommand): Flow<String>
}
