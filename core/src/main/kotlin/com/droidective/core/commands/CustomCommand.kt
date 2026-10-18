package com.droidective.core.commands

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A shell command someone saved, runnable from the app, a widget, a tile or an automation. It runs
 * as the shell user exactly as typed: it's the person's own command, so nothing is quoted.
 *
 * With [offCommand] it's a toggle: [command] turns it on, [offCommand] turns it off, and [check]
 * says which it is when its output contains [onWhenOutputContains].
 */
@Serializable
data class CustomCommand(
    val id: String,
    val name: String,
    val command: String,
    val offCommand: String? = null,
    val check: String? = null,
    val onWhenOutputContains: String? = null,
) {
    val isToggle: Boolean
        get() = offCommand != null

    /** The line to run to move from [currentlyOn], or to run once for a button. */
    fun next(currentlyOn: Boolean?): ShellCommand =
        ShellCommand(if (isToggle && currentlyOn == true) offCommand.orEmpty() else command)

    /** Whether [output] of [check] means "on", or null when this can't be checked. */
    fun isOn(output: String): Boolean? {
        val needle = onWhenOutputContains?.takeIf { it.isNotEmpty() } ?: return null
        return Parse.lines(output).any { needle in it }
    }

    companion object {
        val idPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}

/** Saved commands, kept as one JSON list in `commands.json`. */
class CommandRepository(root: File) {
    private val store =
        JsonFileStore(File(root, "commands.json"), ListSerializer(CustomCommand.serializer())) {
            emptyList()
        }

    @Synchronized fun all(): List<CustomCommand> = store.load()

    @Synchronized fun get(id: String): CustomCommand? = all().firstOrNull { it.id == id }

    @Synchronized
    fun save(command: CustomCommand) {
        require(CustomCommand.idPattern.matches(command.id)) {
            "\"${command.id}\" isn’t a command id"
        }
        require(command.command.isNotBlank()) { "A command needs something to run" }
        store.save(all().filterNot { it.id == command.id } + command)
    }

    @Synchronized fun delete(id: String) = store.save(all().filterNot { it.id == id })
}
