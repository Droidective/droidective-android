package com.droidective.core.reactotron

import kotlinx.serialization.json.JsonPrimitive

/**
 * What the connected apps said, oldest first, as the Reactotron screen lists it.
 *
 * Bounded twice over: by how many entries, and by how many bytes they were as they crossed — a
 * state tree or an API answer can be megabytes, and a busy app sends hundreds a second. The oldest
 * go first. An app's `clear` empties it, as it empties Reactotron's own.
 */
class ReactotronTimeline(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_BYTES,
) {
    /** One thing an app said, or a connection's coming or going. */
    data class Entry(
        val id: Long,
        val at: Long,
        val connection: Int,
        /** The app's name, from its intro; null before it gave one. */
        val app: String?,
        val command: ReactotronCommand,
        val event: ReactotronEvent,
        val size: Int,
    )

    private val entries = ArrayDeque<Entry>()
    private var bytes = 0L
    private var next = 0L

    /** Bumped on every change, for a screen to know there is something new. */
    @Volatile
    var revision = 0L
        private set

    @Synchronized
    fun add(at: Long, connection: Int, app: String?, command: ReactotronCommand, size: Int) {
        if (command.type == CLEAR) {
            entries.clear()
            bytes = 0
        } else {
            entries.addLast(
                Entry(next++, at, connection, app, command, ReactotronEvent.of(command), size)
            )
            bytes += size
            while (entries.size > maxEntries || (bytes > maxBytes && entries.size > 1)) {
                bytes -= entries.removeFirst().size
            }
        }
        revision++
    }

    /** That [connection] ([app]) went, and [why]: a line in the timeline, as Reactotron has. */
    fun gone(at: Long, connection: Int, app: String?, why: String) =
        add(at, connection, app, ReactotronCommand(GONE, JsonPrimitive(why)), why.length)

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
        revision++
    }

    @Synchronized fun snapshot(): List<Entry> = entries.toList()

    @Synchronized fun get(id: Long): Entry? = entries.firstOrNull { it.id == id }

    @Synchronized fun bytes(): Long = bytes

    companion object {
        /** The type a connection's going is kept under; no client sends it. */
        const val GONE = "(disconnected)"

        const val MAX_ENTRIES = 5_000
        const val MAX_BYTES = 32L * 1024 * 1024
        private const val CLEAR = "clear"
    }
}
