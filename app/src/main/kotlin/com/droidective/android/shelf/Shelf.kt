package com.droidective.android.shelf

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import com.droidective.core.features.Features
import com.droidective.core.features.ToolShelf
import com.droidective.core.features.ToolShelfRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The tools this person pinned and opened last, kept, and turned into the launcher's shortcuts
 * whenever they change.
 */
object Shelf {
    private val current = MutableStateFlow(ToolShelf())
    @Volatile private var loaded = false

    val shelf: StateFlow<ToolShelf> = current.asStateFlow()

    /** Reads the kept shelf, once; after that [shelf] is the truth. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            current.value = repository(context).load().known(Features.all.map { it.id }.toSet())
            loaded = true
        }
    }

    fun opened(context: Context, id: String) {
        change(context) { it.opened(id) }
        // Launchers rank their suggestions by what is used, from a shortcut or not.
        ShortcutManagerCompat.reportShortcutUsed(context, ToolShortcuts.idOf(id))
    }

    fun pin(context: Context, id: String, on: Boolean) = change(context) { it.pinning(id, on) }

    private fun change(context: Context, edit: (ToolShelf) -> ToolShelf) {
        load(context)
        val changed = synchronized(this) { edit(current.value).also { current.value = it } }
        repository(context).save(changed)
        ToolShortcuts.publish(context, changed)
    }

    private fun repository(context: Context) = ToolShelfRepository(context.filesDir)
}
