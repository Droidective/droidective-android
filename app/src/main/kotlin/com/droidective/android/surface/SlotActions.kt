package com.droidective.android.surface

import com.droidective.core.automations.Action
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * An action carried in a slot's PendingIntent, as the JSON the automation files use. Only our own
 * PendingIntents reach the receivers that read it, and an action that no longer parses, such as one
 * from an older version, is dropped instead of guessed at.
 */
object SlotActions {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(action: Action): String = json.encodeToString(Action.serializer(), action)

    fun decode(text: String): Action? =
        try {
            json.decodeFromString(Action.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
