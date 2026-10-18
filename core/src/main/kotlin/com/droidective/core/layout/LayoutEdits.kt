package com.droidective.core.layout

/** Pure edits to a layout. Each returns a new layout stamped with [now], or why it refused. */
object LayoutEdits {
    sealed interface Result {
        data class Done(val layout: Layout) : Result

        data class Refused(val reason: String) : Result
    }

    fun add(layout: Layout, item: Item, now: Long): Result =
        when {
            layout.items.size >= layout.kind.maxItems ->
                Result.Refused(
                    "A ${layout.kind.title.lowercase()} holds ${layout.kind.maxItems} items at most."
                )
            layout.items.any { it.key == item.key } -> Result.Refused("That slot is already used.")
            item is Item.Folder && layout.kind == LayoutKind.Folder ->
                Result.Refused("A folder can’t hold another folder.")
            item is Item.Folder && layout.kind == LayoutKind.Notification ->
                Result.Refused("The notification bar can’t open folders.")
            else -> Result.Done(layout.copy(items = layout.items + item, editedAt = now))
        }

    fun remove(layout: Layout, key: String, now: Long): Layout =
        layout.copy(items = layout.items.filterNot { it.key == key }, editedAt = now)

    /**
     * Moves the item at [from] so it ends up at index [to] of the result. Out-of-range indices are
     * clamped, so a drag released past either end lands at that end.
     */
    fun move(layout: Layout, from: Int, to: Int, now: Long): Layout {
        if (from !in layout.items.indices) return layout
        val items = layout.items.toMutableList()
        val item = items.removeAt(from)
        items.add(to.coerceIn(0, items.size), item)
        return layout.copy(items = items, editedAt = now)
    }

    fun restyle(layout: Layout, style: Style, now: Long): Layout =
        layout.copy(style = style, editedAt = now)
}
