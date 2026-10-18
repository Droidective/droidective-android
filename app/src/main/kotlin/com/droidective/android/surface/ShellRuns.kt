package com.droidective.android.surface

import android.content.Context
import com.droidective.android.droidective
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellResult
import com.droidective.core.shell.ShellUnavailableException

/** Runs shell lines as the shell user, for saved commands and the tool screens alike. */
object ShellRuns {
    sealed interface Result {
        /** The last line that ran: the first one that failed, or else the final one. */
        data class Ran(val result: ShellResult) : Result

        /** No shell: Shizuku isn't running and there's no root. */
        data object NoShell : Result

        data class Failed(val message: String) : Result
    }

    /**
     * Runs [commands] in order and stops at the first that fails. Redraws nothing: the caller
     * follows with [Surfaces.refresh], or [Surfaces.refreshBehind] when a screen answers first.
     */
    suspend fun execute(context: Context, commands: List<ShellCommand>): Result {
        require(commands.isNotEmpty()) { "Nothing to run" }
        val app = context.droidective
        app.access.settled()
        val shell = app.access.shell() ?: return Result.NoShell
        var last: ShellResult? = null
        try {
            for (command in commands) {
                last = shell.run(command)
                if (!last.succeeded) break
            }
        } catch (e: ShellUnavailableException) {
            return Result.Failed(e.message ?: "The shell stopped responding")
        }
        return Result.Ran(checkNotNull(last))
    }
}
