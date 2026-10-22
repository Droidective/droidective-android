package com.droidective.android.proxy

import android.content.Context
import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * A list someone builds up one item at a time — the mocks, the breakpoints — kept as [name] in the
 * app's files, in the order it was made. It is read once; after that [items] is the truth, and each
 * change is written at once and handed to [apply], so a running proxy has it for its next request.
 */
internal class KeptList<T>(
    private val name: String,
    serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    private val apply: (List<T>) -> Unit,
) {
    private val serializer = ListSerializer(serializer)
    private val kept = MutableStateFlow<List<T>>(emptyList())
    @Volatile private var loaded = false

    val items: StateFlow<List<T>> = kept.asStateFlow()

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            kept.value = store(context).load()
            loaded = true
        }
    }

    /** Keeps [item] in the place of the one with its id, or after the rest. */
    fun save(context: Context, item: T) {
        val id = idOf(item)
        change(context) { all ->
            if (all.any { idOf(it) == id }) all.map { if (idOf(it) == id) item else it }
            else all + item
        }
    }

    fun remove(context: Context, id: String) =
        change(context) { all -> all.filterNot { idOf(it) == id } }

    private fun change(context: Context, edit: (List<T>) -> List<T>) {
        load(context)
        // Written under the lock too, or two quick changes could reach the file out of order.
        val changed =
            synchronized(this) {
                edit(kept.value).also {
                    store(context).save(it)
                    kept.value = it
                }
            }
        apply(changed)
    }

    private fun store(context: Context) =
        JsonFileStore(File(context.filesDir, name), serializer) { emptyList() }
}
