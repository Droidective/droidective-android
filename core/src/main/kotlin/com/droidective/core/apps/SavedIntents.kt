package com.droidective.core.apps

import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable data class SavedIntent(val name: String, val spec: IntentSpec)

/** The intents someone named to send again, kept in `intents.json`, most recent first. */
class SavedIntents(root: File) {
    private val store =
        JsonFileStore(File(root, "intents.json"), ListSerializer(SavedIntent.serializer())) {
            emptyList()
        }

    @Synchronized fun all(): List<SavedIntent> = store.load()

    /** Saves [intent], replacing one of the same name. */
    @Synchronized
    fun save(intent: SavedIntent) {
        store.save(listOf(intent) + all().filterNot { it.name == intent.name })
    }

    @Synchronized
    fun remove(name: String) {
        store.save(all().filterNot { it.name == name })
    }
}
