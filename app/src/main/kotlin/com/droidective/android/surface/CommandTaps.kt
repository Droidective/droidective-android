package com.droidective.android.surface

import android.content.Context
import com.droidective.android.droidective
import com.droidective.core.commands.CustomCommand
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException

/** Runs saved custom commands for every surface, as the shell user. */
object CommandTaps {
    /** "on" or "off" for a command toggle with a check, else null. */
    suspend fun stateOf(context: Context, command: CustomCommand): String? {
        val check = command.check?.takeIf { it.isNotBlank() } ?: return null
        val shell = context.droidective.access.shell() ?: return null
        return try {
            val output = shell.run(ShellCommand(check))
            command.isOn(output.stdout)?.let { if (it) "on" else "off" }
        } catch (_: ShellUnavailableException) {
            null
        }
    }

    /**
     * Runs [command], then redraws every surface before returning, which takes a few seconds with
     * several widgets. A background tap needs that; a screen answers first with [execute].
     */
    suspend fun run(context: Context, command: CustomCommand): ShellRuns.Result =
        execute(context, command).also { if (it is ShellRuns.Result.Ran) Surfaces.refresh(context) }

    /** Runs [command] (its off line when it's an "on" toggle). Redraws nothing. */
    suspend fun execute(context: Context, command: CustomCommand): ShellRuns.Result {
        context.droidective.access.settled()
        val on = if (command.isToggle) stateOf(context, command) == "on" else null
        return ShellRuns.execute(context, listOf(command.next(on)))
    }
}
