package com.droidective.core.reactotron

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One frame a Reactotron client sent: its type and payload, the rest of the envelope as it came.
 * The payload's `~~~ … ~~~` markers are undone on the way in, as the official server does.
 */
data class ReactotronCommand(
    val type: String,
    val payload: JsonElement? = null,
    val important: Boolean = false,
    val date: String? = null,
    val deltaTime: Double? = null,
) {
    companion object {
        /** [text] as a command, or null when it isn't the JSON object every frame is. */
        fun parse(text: String): ReactotronCommand? {
            val root =
                try {
                    Json.parseToJsonElement(text) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } ?: return null
            return ReactotronCommand(
                type = (root["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                payload = root["payload"]?.let(::repaired),
                important = lenient(root["important"]),
                date = (root["date"] as? JsonPrimitive)?.contentOrNull,
                deltaTime = (root["deltaTime"] as? JsonPrimitive)?.doubleOrNull,
            )
        }

        /**
         * The client writes what JSON can't hold as marker strings — `"~~~ undefined ~~~"`, `"~~~
         * false ~~~"`, `"~~~ anonymous() ~~~"` — and the official server's
         * `repair-serialization.ts` turns them back on every frame. The falsy ones become what they
         * stood for; any other loses its tildes and stays text.
         */
        fun repaired(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject -> JsonObject(element.mapValues { repaired(it.value) })
                is JsonArray -> JsonArray(element.map(::repaired))
                is JsonPrimitive ->
                    if (element.isString) marker(element.content) ?: element else element
            }

        private fun marker(text: String): JsonElement? {
            // Markers are short; the length bound keeps megabyte log strings out of lowercase().
            if (text.length !in MARKER_LENGTHS) return null
            if (!text.startsWith(MARKER) || !text.endsWith(MARKER_END)) return null
            return when (text.lowercase()) {
                "~~~ null ~~~",
                // JSON has no undefined; null is the nearest it comes.
                "~~~ undefined ~~~" -> JsonNull
                "~~~ false ~~~" -> JsonPrimitive(false)
                "~~~ zero ~~~" -> JsonPrimitive(0)
                "~~~ empty string ~~~" -> JsonPrimitive("")
                else ->
                    JsonPrimitive(text.substring(MARKER.length, text.length - MARKER_END.length))
            }
        }

        /** `important` arrives as a boolean, or as a marker string such as `"~~~ false ~~~"`. */
        private fun lenient(element: JsonElement?): Boolean {
            val primitive = element as? JsonPrimitive ?: return false
            return primitive.booleanOrNull ?: (primitive.isString && "true" in primitive.content)
        }

        private const val MARKER = "~~~ "
        private const val MARKER_END = " ~~~"
        private val MARKER_LENGTHS = 10 until 2048
    }
}

/** The member [name] of an object, or null for anything else. */
internal operator fun JsonElement?.get(name: String): JsonElement? =
    (this as? JsonObject)?.get(name)

internal val JsonElement?.text: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal val JsonElement?.number: Double?
    get() = (this as? JsonPrimitive)?.doubleOrNull

internal val JsonElement?.list: List<JsonElement>
    get() = (this as? JsonArray).orEmpty()
