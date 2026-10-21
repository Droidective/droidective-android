package com.droidective.core.features

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolShelfTest {
    @Test
    fun theToolOpenedLastComesFirst() {
        val shelf = ToolShelf().opened("logcat").opened("apps").opened("logcat")
        assertEquals(listOf("logcat", "apps"), shelf.recent)
    }

    @Test
    fun recentKeepsADozen() {
        val shelf = (1..20).fold(ToolShelf()) { s, n -> s.opened("tool-$n") }
        assertEquals(ToolShelf.KEPT_RECENT, shelf.recent.size)
        assertEquals("tool-20", shelf.recent.first())
    }

    @Test
    fun pinningKeepsTheOrderTheyWerePinnedIn() {
        val shelf =
            ToolShelf().pinning("logcat", true).pinning("apps", true).pinning("logcat", true)
        assertEquals(listOf("apps", "logcat"), shelf.pinned)
        assertTrue(shelf.isPinned("apps"))
        assertEquals(listOf("logcat"), shelf.pinning("apps", false).pinned)
        assertFalse(shelf.pinning("apps", false).isPinned("apps"))
    }

    @Test
    fun recentLeavesOutWhatIsPinned() {
        val shelf = ToolShelf().opened("a").opened("b").opened("c").pinning("b", true)
        assertEquals(listOf("c", "a"), shelf.recentNotPinned(4))
        // Unpinned, it is back where its last opening put it.
        assertEquals(listOf("c", "b", "a"), shelf.pinning("b", false).recentNotPinned(4))
    }

    @Test
    fun theLauncherGetsPinnedThenRecent() {
        val shelf = ToolShelf().opened("a").opened("b").opened("c").opened("d").pinning("x", true)
        assertEquals(listOf("x", "d", "c", "b"), shelf.shortcuts(4))
        assertEquals(listOf("x"), shelf.shortcuts(1))
        assertEquals(emptyList(), ToolShelf().shortcuts(4))
    }

    @Test
    fun toolsNoLongerInTheAppAreDropped() {
        val shelf = ToolShelf(pinned = listOf("gone", "apps"), recent = listOf("logcat", "gone"))
        assertEquals(
            ToolShelf(pinned = listOf("apps"), recent = listOf("logcat")),
            shelf.known(setOf("apps", "logcat")),
        )
    }

    @Test
    fun theShelfIsKept() {
        val repository = ToolShelfRepository(createTempDirectory("shelf").toFile())
        assertEquals(ToolShelf(), repository.load())
        val shelf = ToolShelf(pinned = listOf("apps"), recent = listOf("logcat"))
        repository.save(shelf)
        assertEquals(shelf, repository.load())
    }
}
