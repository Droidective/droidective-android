package com.droidective.core.reactotron

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the connected apps say the watched state paths hold. A store plugin answers
 * `state.values.subscribe` with every watched value, and again whenever its state changes, so an
 * app's latest `state.values.change` is the whole picture for it. A path ending in `.*` comes back
 * as one value for each key under it.
 */
class ReactotronWatch {
    /** A value [connection] said, at [path], under the watched path it answers. */
    data class Reading(val connection: Int, val path: String, val value: JsonElement)

    private val values = LinkedHashMap<Int, List<Pair<String, JsonElement>>>()

    /** Takes in [command] when it says what watched paths hold; whether it did. */
    @Synchronized
    fun heard(connection: Int, command: ReactotronCommand): Boolean {
        val change = ReactotronEvent.of(command) as? ReactotronEvent.StateChange ?: return false
        values[connection] = change.changes
        return true
    }

    @Synchronized
    fun gone(connection: Int) {
        values.remove(connection)
    }

    /** The values each connection last said, by connection. */
    @Synchronized fun all(): Map<Int, List<Pair<String, JsonElement>>> = values.toMap()

    companion object {
        const val SUBSCRIBE = "state.values.subscribe"

        /** What asks an app to send what [paths] hold now, and whenever its state changes. */
        fun subscribe(paths: List<String>): JsonElement =
            JsonObject(mapOf("paths" to JsonArray(paths.map(::JsonPrimitive))))

        /**
         * [path] trimmed, or null when that leaves nothing: an empty path would watch the whole
         * store and send all of it on every change, which State reads once instead.
         */
        fun path(path: String): String? = path.trim().takeIf { it.isNotEmpty() }

        /**
         * Whether [path], as an app said it, answers one of [watched]: the path itself, a key under
         * a watched `x.*`, or `x` alone when there is nothing under it. An app says nothing when
         * its last path goes, so what it said before stays until this leaves it out.
         */
        fun answers(path: String, watched: List<String>): Boolean = watched.any { each ->
            val parent = each.removeSuffix(STAR)
            each == path || (each.endsWith(STAR) && (path == parent || path.startsWith("$parent.")))
        }

        /**
         * Each of [watched], in order, with what the [connected] apps last said it holds in
         * [values]: the path itself, or a reading for each key under an `x.*`. A value that answers
         * two watched paths is under both; a path nothing answers has no readings.
         */
        fun readings(
            watched: List<String>,
            values: Map<Int, List<Pair<String, JsonElement>>>,
            connected: Set<Int>,
        ): List<Pair<String, List<Reading>>> = watched.map { each ->
            each to
                values
                    .filterKeys { it in connected }
                    .flatMap { (connection, said) ->
                        said
                            .filter { (path, _) -> answers(path, listOf(each)) }
                            .map { (path, value) -> Reading(connection, path, value) }
                    }
        }

        private const val STAR = ".*"
    }
}
