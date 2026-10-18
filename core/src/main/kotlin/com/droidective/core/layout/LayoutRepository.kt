package com.droidective.core.layout

import com.droidective.core.store.JsonFileStore
import java.io.File
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

    @Synchronized
    fun delete(id: String) {
        store(id).delete()
        bindings.save(bindings.load().filterValues { it != id })
    }

    @Synchronized
    fun bind(widgetId: Int, layoutId: String) {
        bindings.save(bindings.load() + (widgetId.toString() to layoutId))
    }

    @Synchronized
    fun layoutFor(widgetId: Int): Layout? = bindings.load()[widgetId.toString()]?.let(::get)

    @Synchronized
    fun widgetsShowing(layoutId: String): List<Int> =
        bindings.load().filterValues { it == layoutId }.keys.mapNotNull { it.toIntOrNull() }

    /** Forgets [widgetId]. Its layout stays, so a widget removed by accident can be put back. */
    @Synchronized
    fun unbind(widgetId: Int) {
        bindings.save(bindings.load() - widgetId.toString())
    }

    companion object {
        /** Ids become file names, so only these characters: no paths, no dots. */
        val validId = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}
