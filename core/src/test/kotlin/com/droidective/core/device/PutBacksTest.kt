package com.droidective.core.device

import com.droidective.core.store.JsonFileStore
import com.droidective.core.testing.FakeShell
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer

class PutBacksTest {
    private val file = File(createTempDirectory().toFile(), "put-backs.json")

    private var now = 1_000L

    /** A new instance over the same file, as the next process would have. */
    private fun putBacks() =
        PutBacks(JsonFileStore(file, ListSerializer(PutBack.serializer())) { emptyList() }) { now }

    private val showTaps =
        PutBack(
            "screen-record",
            "Show taps",
            listOf("settings delete system show_touches"),
            toggles = mapOf("demo-mode" to "off"),
        )
    private val set = mutableListOf<String>()
    private val toggles = SetToggle { id, state -> set += "$id=$state" }
    private val doze =
        PutBack("doze", "Doze", listOf("dumpsys deviceidle unforce", "dumpsys battery reset"))

    @Test
    fun whatTheAppDidntLiveToUndoIsUndoneByTheNextOne() = runTest {
        putBacks().hold(showTaps)
        putBacks().hold(doze)
        val shell = FakeShell()
        val next = putBacks()
        assertEquals(
            listOf("screen-record", "doze"),
            next.putBackAll(shell, toggles, before = 5_000).map { it.id },
        )
        assertEquals(
            listOf(
                "settings delete system show_touches",
                "dumpsys deviceidle unforce",
                "dumpsys battery reset",
            ),
            shell.invocations,
        )
        assertEquals(listOf("demo-mode=off"), set, "toggles first, through the app's own")
        assertTrue(next.owed().isEmpty())
    }

    @Test
    fun aChangeUndoneTheUsualWayIsntUndoneAgain() = runTest {
        val putBacks = putBacks()
        putBacks.hold(showTaps)
        putBacks.release("screen-record")
        val shell = FakeShell()
        assertTrue(putBacks().putBackAll(shell, toggles, before = 5_000).isEmpty())
        assertTrue(shell.invocations.isEmpty())
    }

    @Test
    fun makingAChangeAgainFirstUndoesWhatTheLastOneLeft() = runTest {
        val putBacks = putBacks()
        putBacks.hold(showTaps)
        putBacks.hold(doze)
        val shell = FakeShell()
        putBacks.clear("screen-record", shell, toggles)
        assertEquals(listOf("settings delete system show_touches"), shell.invocations)
        assertEquals(listOf("doze"), putBacks.owed().map { it.id }, "only that change")
    }

    @Test
    fun holdingTheSameChangeAgainKeepsOnlyTheNewestWayBack() = runTest {
        val putBacks = putBacks()
        putBacks.hold(showTaps)
        val again = showTaps.copy(lines = listOf("settings put system show_touches 1"))
        putBacks.hold(again)
        assertEquals(listOf(again.lines), putBacks.owed().map { it.lines })
    }

    @Test
    fun withNoShellItStaysOwedForTheNextTry() = runTest {
        val putBacks = putBacks()
        putBacks.hold(doze)
        val gone = FakeShell().apply { unavailable = true }
        assertTrue(putBacks.putBackAll(gone, toggles, before = 5_000).isEmpty())
        assertEquals(listOf("doze"), putBacks().owed().map { it.id })
    }

    @Test
    fun aNewProcessLeavesAloneWhatItChangedItself() = runTest {
        putBacks().hold(doze)
        now = 6_000
        val next = putBacks()
        // Started at 5 000 by a tap that began a recording at 6 000, before this ran.
        next.hold(showTaps)
        val shell = FakeShell()
        assertEquals(listOf("doze"), next.putBackAll(shell, toggles, before = 5_000).map { it.id })
        assertEquals(listOf("screen-record"), next.owed().map { it.id })
    }
}
