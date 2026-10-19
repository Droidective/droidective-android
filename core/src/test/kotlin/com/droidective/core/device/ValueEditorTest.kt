package com.droidective.core.device

import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellResult
import com.droidective.core.shell.ShellUnavailableException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class ValueEditorTest {
    private val dir = createTempDirectory("editor").toFile()
    private val shell = GlobalSettingsShell()
    private val changes = ValueChanges(dir)
    private val editor = ValueEditor(shell, changes, now = { 42 })

    @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

    @Test
    fun aChangeRecordsWhatItReplaced() = runTest {
        shell.values["animator_duration_scale"] = "1.0"
        val change = ValueChange(ValueTable.Global, "animator_duration_scale", "1.0", "0.5", 42)
        assertEquals(
            ValueOutcome.Changed(change),
            editor.set(ValueTable.Global, "animator_duration_scale", "0.5"),
        )
        assertEquals(listOf(change), changes.all())
    }

    @Test
    fun undoPutsTheFirstValueBackAndForgetsTheChange() = runTest {
        shell.values["k"] = "1"
        editor.set(ValueTable.Global, "k", "2")
        editor.set(ValueTable.Global, "k", "3")
        assertEquals(ValueOutcome.Undone, editor.undo(changes.all().single()))
        assertEquals("1", shell.values["k"])
        assertEquals(emptyList(), changes.all())
    }

    @Test
    fun undoingANewValueDeletesIt() = runTest {
        editor.set(ValueTable.Global, "new_key", "on")
        assertEquals(null, changes.all().single().before)
        assertEquals(ValueOutcome.Undone, editor.undo(changes.all().single()))
        assertEquals(false, "new_key" in shell.values)
    }

    @Test
    fun aValueAndroidIgnoresIsKeptNotRecorded() = runTest {
        shell.values["locked"] = "1"
        shell.ignored += "locked"
        assertEquals(ValueOutcome.Kept("1"), editor.set(ValueTable.Global, "locked", "0"))
        assertEquals(ValueOutcome.Unchanged, editor.set(ValueTable.Global, "locked", "1"))
        assertEquals(emptyList(), changes.all())
    }

    @Test
    fun aFailedWriteSaysWhatTheShellSaid() = runTest {
        shell.failWrites = "Permission denial: writing to settings requires WRITE_SECURE_SETTINGS"
        assertEquals(
            ValueOutcome.Refused(
                "Permission denial: writing to settings requires WRITE_SECURE_SETTINGS"
            ),
            editor.set(ValueTable.Global, "k", "1"),
        )
        assertEquals(emptyList(), changes.all())
    }

    @Test
    fun fixedValuesAreRefusedBeforeAnythingRuns() = runTest {
        assertIs<ValueOutcome.Refused>(editor.set(ValueTable.Props, "ro.debuggable", "1"))
        assertIs<ValueOutcome.Refused>(editor.set(ValueTable.Props, "debug.layout", null))
        assertEquals(emptyList(), shell.ran)
    }

    @Test
    fun aStoppedShellIsRefusedNotThrown() = runTest {
        shell.gone = true
        assertEquals(
            ValueOutcome.Refused("Shizuku stopped"),
            editor.set(ValueTable.Global, "k", "1"),
        )
    }

    /** The global settings table, as `settings get/put/delete global` would see it. */
    private class GlobalSettingsShell : Shell {
        val values = mutableMapOf<String, String>()
        val ignored = mutableSetOf<String>()
        val ran = mutableListOf<String>()
        var failWrites: String? = null
        var gone = false

        override suspend fun run(command: ShellCommand): ShellResult {
            if (gone) throw ShellUnavailableException("Shizuku stopped")
            ran += command.line
            val words = command.line.split(' ').map { it.removeSurrounding("'") }
            val key = words[3]
            return when (words[1]) {
                "get" -> ShellResult(0, (values[key] ?: "null") + "\n", "")
                else -> write(words[1], key, words.getOrNull(4))
            }
        }

        private fun write(verb: String, key: String, value: String?): ShellResult {
            failWrites?.let {
                return ShellResult(1, "", "$it\n")
            }
            if (key !in ignored) {
                if (verb == "put") values[key] = value.orEmpty() else values.remove(key)
            }
            return ShellResult(0, "", "")
        }

        override fun stream(command: ShellCommand): Flow<String> = emptyFlow()
    }
}
