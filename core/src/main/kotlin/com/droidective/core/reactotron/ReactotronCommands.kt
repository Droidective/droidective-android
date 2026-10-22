package com.droidective.core.reactotron

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The custom commands each connected app has registered — `Reactotron.onCustomCommand(...)` in the
 * app — which Reactotron's desktop shows as buttons, and which run in the app when pressed.
 *
 * An app registers a command once per connection and takes it back when its handler is removed or
 * it registers the same name again; a connection going takes all of its commands with it.
 */
class ReactotronCommands {
    /** One command an app offers, with the names of the text arguments it asks for. */
    data class Command(
        val connection: Int,
        val id: Int,
        val name: String,
        val title: String?,
        val description: String?,
        val args: List<String>,
    ) {
        /** What a button says: the title the app gave it, or else its name. */
        val label: String
            get() = title?.takeIf { it.isNotBlank() } ?: name
    }

    private val commands = LinkedHashMap<Pair<Int, Int>, Command>()

    /**
     * Takes in what [command] says about commands, if anything: whether it was a register or an
     * unregister, which a timeline needn't show.
     */
    @Synchronized
    fun heard(connection: Int, command: ReactotronCommand): Boolean {
        val payload = command.payload
        val id = payload["id"].number?.toInt() ?: return false
        when (command.type) {
            REGISTER -> {
                val name = payload["command"].text?.takeIf { it.isNotEmpty() } ?: return false
                commands[connection to id] =
                    Command(
                        connection,
                        id,
                        name,
                        payload["title"].text,
                        payload["description"].text,
                        payload["args"].list.mapNotNull { it["name"].text },
                    )
            }
            UNREGISTER -> commands.remove(connection to id)
            else -> return false
        }
        return true
    }

    @Synchronized
    fun gone(connection: Int) {
        commands.keys.removeAll { it.first == connection }
    }

    @Synchronized fun all(): List<Command> = commands.values.toList()

    companion object {
        const val REGISTER = "customCommand.register"
        const val UNREGISTER = "customCommand.unregister"

        /** The type the server sends to run one. */
        const val RUN = "custom"

        /**
         * What runs [command] with [args]: the client matches on `command` and hands `args` to the
         * app's handler, an object of text values by name.
         */
        fun run(command: Command, args: Map<String, String>): JsonElement =
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive(command.name),
                    "args" to
                        JsonObject(
                            command.args.associateWith { JsonPrimitive(args[it].orEmpty()) }
                        ),
                )
            )

        /** What asks an app for the state at [path], its value or the keys under it. */
        fun stateRequest(path: String, keys: Boolean): Pair<String, JsonElement> =
            (if (keys) "state.keys.request" else "state.values.request") to
                JsonObject(mapOf("path" to JsonPrimitive(path.trim())))
    }
}
