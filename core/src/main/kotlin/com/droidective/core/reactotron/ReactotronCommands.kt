package com.droidective.core.reactotron

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

        /**
         * The command [app] offers by [name] now, or null when it offers none. An app that has just
         * reconnected can briefly have two connections; the newer one is the live one.
         */
        fun find(
            commands: List<Command>,
            appOf: (connection: Int) -> String?,
            app: String,
            name: String,
        ): Command? =
            commands
                .filter { it.name == name && appOf(it.connection) == app }
                .maxByOrNull { it.connection }

        /**
         * What asks an app for its whole state, to keep: its store plugin, reactotron-redux or
         * reactotron-mst, answers with [BACKUP_RESPONSE].
         */
        const val BACKUP_REQUEST = "state.backup.request"
        const val BACKUP_RESPONSE = "state.backup.response"

        /** What has an app's store plugin replace its state with the one sent. */
        const val RESTORE_REQUEST = "state.restore.request"

        /** The state [command] gives back when it answers [BACKUP_REQUEST], or null. */
        fun backedUp(command: ReactotronCommand): JsonElement? =
            command.payload["state"].takeIf {
                command.type == BACKUP_RESPONSE && it != null && it !is JsonNull
            }

        /** What puts [state] back in place of the app's own. */
        fun restore(state: JsonElement): JsonElement = JsonObject(mapOf("state" to state))

        /** What asks an app for the state at [path], its value or the keys under it. */
        fun stateRequest(path: String, keys: Boolean): Pair<String, JsonElement> =
            (if (keys) "state.keys.request" else "state.values.request") to
                JsonObject(mapOf("path" to JsonPrimitive(path.trim())))
    }
}
