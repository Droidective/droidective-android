package com.droidective.core.reactotron

import com.droidective.core.store.JsonFileStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One app's whole state, as its store plugin gave it, kept to put back; [bytes] as kept. */
@Serializable
data class ReactotronSnapshot(
    val id: String,
    val name: String,
    val app: String,
    val takenAt: Long,
    val bytes: Long,
)

/**
 * The snapshots kept in [dir], newest first: a small index, and each state in a file of its own,
 * read only to restore or show it. A state can be megabytes, and a list of twenty shouldn't hold
 * them all. Twenty, or 32 MB, whichever comes first; the oldest go.
 */
class ReactotronSnapshots(private val dir: File) {
    private val index =
        JsonFileStore(File(dir, INDEX), ListSerializer(ReactotronSnapshot.serializer())) {
            emptyList()
        }

    @Synchronized fun all(): List<ReactotronSnapshot> = index.load()

    /**
     * Keeps [state], which [app] gave, as the newest snapshot.
     *
     * @return every snapshot kept now, newest first.
     * @throws IOException when the state can't be written.
     */
    @Synchronized
    fun keep(
        app: String,
        name: String,
        state: JsonElement,
        takenAt: Long,
        id: String = UUID.randomUUID().toString(),
    ): List<ReactotronSnapshot> {
        val text = Json.encodeToString(JsonElement.serializer(), state)
        dir.mkdirs()
        val file = File(dir, "$id.json")
        try {
            file.writeText(text)
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        val snapshot = ReactotronSnapshot(id, name, app, takenAt, text.toByteArray().size.toLong())
        val kept = bounded(listOf(snapshot) + index.load())
        index.save(kept)
        sweep(kept)
        return kept
    }

    /** The state snapshot [id] holds, or null when there's no such snapshot or it won't read. */
    @Synchronized
    fun state(id: String): JsonElement? {
        if (index.load().none { it.id == id }) return null
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return try {
            Json.parseToJsonElement(file.readText())
        } catch (_: SerializationException) {
            null
        }
    }

    @Synchronized
    fun rename(id: String, name: String): List<ReactotronSnapshot> {
        val renamed = index.load().map { if (it.id == id) it.copy(name = name) else it }
        index.save(renamed)
        return renamed
    }

    @Synchronized
    fun delete(id: String): List<ReactotronSnapshot> {
        val kept = index.load().filterNot { it.id == id }
        index.save(kept)
        sweep(kept)
        return kept
    }

    /** Removes every state file the index doesn't name: dropped, deleted, or written by a crash. */
    private fun sweep(kept: List<ReactotronSnapshot>) {
        val named = kept.map { "${it.id}.json" }.toSet() + INDEX
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.filterNot { it.name in named }
            ?.forEach { it.delete() }
    }

    companion object {
        const val MAX_COUNT = 20
        const val MAX_BYTES = 32L * 1024 * 1024
        private const val INDEX = "index.json"

        /** [all], newest first, cut to the bounds; the newest stays whatever its size. */
        fun bounded(all: List<ReactotronSnapshot>): List<ReactotronSnapshot> {
            var total = 0L
            val kept = mutableListOf<ReactotronSnapshot>()
            for (snapshot in all) {
                total += snapshot.bytes
                val fits = kept.size < MAX_COUNT && total <= MAX_BYTES
                if (!fits && kept.isNotEmpty()) break
                kept += snapshot
            }
            return kept
        }
    }
}
