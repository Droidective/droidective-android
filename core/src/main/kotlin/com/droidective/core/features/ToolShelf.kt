package com.droidective.core.features

import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable

/**
 * The tools somebody reaches for: the ones they pinned, and the ones they opened last.
 *
 * Thirty-odd tools in one list behind a search box are found by searching every time. The shelf
 * puts the few that matter to this person first, and the launcher's shortcuts are made from it.
 *
 * @property pinned in the order they were pinned.
 * @property recent most recent first, pinned tools included, so unpinning one keeps its place.
 */
@Serializable
data class ToolShelf(
    val pinned: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
) {
    fun opened(id: String): ToolShelf =
        copy(recent = (listOf(id) + (recent - id)).take(KEPT_RECENT))

    fun pinning(id: String, on: Boolean): ToolShelf =
        copy(pinned = if (on) (pinned - id) + id else pinned - id)

    fun isPinned(id: String): Boolean = id in pinned

    /** The recent tools that aren't pinned, which is what a Recent row shows under Pinned. */
    fun recentNotPinned(limit: Int): List<String> = recent.filterNot(::isPinned).take(limit)

    /** What the launcher offers: pinned first, then the most recent of the rest, [limit] in all. */
    fun shortcuts(limit: Int): List<String> = (pinned + recentNotPinned(limit)).take(limit)

    /** Without the ids no longer a tool, so one removed from the app doesn't linger. */
    fun known(ids: Set<String>): ToolShelf =
        ToolShelf(pinned.filter(ids::contains), recent.filter(ids::contains))

    companion object {
        /** More than any row shows, so pinning and unpinning doesn't empty Recent. */
        const val KEPT_RECENT = 12
    }
}

/** The shelf, kept in `tool-shelf.json`. */
class ToolShelfRepository(root: File) {
    private val store =
        JsonFileStore(File(root, "tool-shelf.json"), ToolShelf.serializer()) { ToolShelf() }

    fun load(): ToolShelf = store.load()

    fun save(shelf: ToolShelf) = store.save(shelf)
}
