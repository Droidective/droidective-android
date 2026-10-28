package com.droidective.core.layout

import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Every layout as its own JSON file under `layouts/`, plus which home-screen widget shows which
 * layout. Files, not a database, so an agent or the Mac can read and write them too.
 */
class LayoutRepository(private val root: File) {
    private val layoutsDir = File(root, "layouts")
    private val bindings =
        JsonFileStore(
            File(root, "widget-bindings.json"),
            MapSerializer(String.serializer(), String.serializer()),
        ) {
            emptyMap()
        }

    private fun store(id: String): JsonFileStore<Layout?> {
        require(validId.matches(id)) { "\"$id\" isn't a layout id" }
        return JsonFileStore(File(layoutsDir, "$id.json"), Layout.serializer().nullable) { null }
    }

    @Synchronized
    fun all(): List<Layout> =
        layoutsDir
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { store(it.nameWithoutExtension).load() }
            .sortedByDescending { it.editedAt }

    @Synchronized fun get(id: String): Layout? = store(id).load()

    @Synchronized fun save(layout: Layout) = store(layout.id).save(layout)

    /** Takes every cell that runs [commandId] out of every layout, once the command is deleted. */
    @Synchronized
    fun dropCommand(commandId: String, now: Long) {
        all().forEach { layout ->
            val kept = layout.items.filterNot { it is Item.Command && it.commandId == commandId }
            if (kept.size != layout.items.size) save(layout.copy(items = kept, editedAt = now))
        }
    }

    /**
     * Takes the layout away. A widget on the home screen that shows it keeps pointing at it, so it
     * can say its layout was deleted ([isBound]) rather than quietly starting over as a new one: an
     * app can't take a widget off the home screen itself.
     */
    @Synchronized
    fun delete(id: String) {
        store(id).delete()
    }

    @Synchronized
    fun bind(widgetId: Int, layoutId: String) {
        bindings.save(bindings.load() + (widgetId.toString() to layoutId))
    }

    @Synchronized
    fun layoutFor(widgetId: Int): Layout? = bindings.load()[widgetId.toString()]?.let(::get)

    /** Whether [widgetId] was given a layout, even one deleted since. */
    @Synchronized
    fun isBound(widgetId: Int): Boolean = bindings.load().containsKey(widgetId.toString())

    /**
     * Notes that [layoutId] is about to be placed on the home screen from the app: the next widget
     * that arrives with no layout of its own shows it ([takePlacing]), rather than a new starter.
     */
    @Synchronized fun placing(layoutId: String, now: Long) = placings.save(Placing(layoutId, now))

    /** The layout being placed, once, if it was asked for within the last minute. */
    @Synchronized
    fun takePlacing(now: Long): String? {
        val placing = placings.load() ?: return null
        placings.save(null)
        return placing.layoutId.takeIf { now - placing.at in 0..PLACING_MILLIS }
    }

    /** Follows widgets a launcher restored under new ids (a backup, a new phone). */
    @Synchronized
    fun restored(oldIds: List<Int>, newIds: List<Int>) {
        val now = bindings.load()
        val moved =
            oldIds.zip(newIds).mapNotNull { (old, new) ->
                now[old.toString()]?.let { new.toString() to it }
            }
        bindings.save(now - oldIds.map(Int::toString).toSet() + moved)
    }

    @Synchronized
    fun widgetsShowing(layoutId: String): List<Int> =
        bindings.load().filterValues { it == layoutId }.keys.mapNotNull { it.toIntOrNull() }

    /** Forgets [widgetId]. Its layout stays, so a widget removed by accident can be put back. */
    @Synchronized
    fun unbind(widgetId: Int) {
        bindings.save(bindings.load() - widgetId.toString())
    }

    private val placings =
        JsonFileStore(File(root, "widget-placing.json"), Placing.serializer().nullable) { null }

    /** A layout the app asked the launcher to place, and when. */
    @Serializable private data class Placing(val layoutId: String, val at: Long)

    companion object {
        /** How long a launcher has to place a widget the app asked for. */
        const val PLACING_MILLIS = 60_000L

        /** Ids become file names, so only these characters: no paths, no dots. */
        val validId = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}
