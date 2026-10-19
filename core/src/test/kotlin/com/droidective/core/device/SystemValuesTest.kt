package com.droidective.core.device

import com.droidective.core.shell.Parse
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Against `settings list secure` and `cmd device_config list` from an API 36 emulator
 * (fixtures/settings/), with the device's identifiers scrubbed.
 */
class SystemValuesTest {
    private val fixtures = File(System.getProperty("fixtures.dir"), "settings")
    private val dir = createTempDirectory("values").toFile()

    @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

    @Test
    fun readsSettingsKeepingEqualsSignsInValues() {
        val values =
            SystemValues.parse(ValueTable.Secure, File(fixtures, "secure-api36.txt").readText())
        assertEquals(140, values.size)
        assertEquals(
            values.map { it.key }.sortedWith(String.CASE_INSENSITIVE_ORDER),
            values.map { it.key },
        )
        val cert = values.first { it.key == "config_update_certificate" }.value.orEmpty()
        assertTrue(cert.endsWith("=="), "a base64 value keeps its padding")
        assertEquals("", values.first { it.key == "immersive_mode_confirmations" }.value)
    }

    @Test
    fun readsFlagsAsNamespaceSlashName() {
        val flags =
            SystemValues.parse(
                ValueTable.Flags,
                File(fixtures, "device-config-api36-head.txt").readText(),
            )
        assertEquals(60, flags.size)
        assertTrue(flags.all { '/' in it.key })
    }

    @Test
    fun nullIsNoValue() {
        assertEquals(
            listOf(SystemValue("a", null), SystemValue("b", "x=y")),
            SystemValues.parse(ValueTable.Global, "b=x=y\r\na=null\nnot a value\n"),
        )
    }

    @Test
    fun writesQuotedToTheRightTable() {
        assertEquals(
            "settings put global 'animator_duration_scale' '0.5'",
            SystemValues.put(ValueTable.Global, "animator_duration_scale", "0.5").line,
        )
        assertEquals(
            "settings put secure 'x' 'it'\\''s'",
            SystemValues.put(ValueTable.Secure, "x", "it's").line,
        )
        assertEquals(
            "setprop 'debug.layout' 'true'",
            SystemValues.put(ValueTable.Props, "debug.layout", "true").line,
        )
        assertEquals(
            "cmd device_config put 'privacy' 'location_indicators_enabled' 'true'",
            SystemValues.put(ValueTable.Flags, "privacy/location_indicators_enabled", "true").line,
        )
        assertEquals(
            "cmd device_config delete 'privacy' 'x'",
            SystemValues.delete(ValueTable.Flags, "privacy/x").line,
        )
        assertEquals("settings delete system 'x'", SystemValues.delete(ValueTable.System, "x").line)
        assertFailsWith<IllegalArgumentException> { SystemValues.delete(ValueTable.Props, "a") }
        assertFailsWith<IllegalArgumentException> {
            SystemValues.put(ValueTable.Flags, "noslash", "1")
        }
    }

    @Test
    fun readsOneValueTheWayEachTableSaysNone() {
        assertEquals(
            "cmd device_config get 'privacy' 'x'",
            SystemValues.get(ValueTable.Flags, "privacy/x").line,
        )
        assertEquals("settings get secure 'k'", SystemValues.get(ValueTable.Secure, "k").line)
        assertEquals(
            "getprop 'debug.layout'",
            SystemValues.get(ValueTable.Props, "debug.layout").line,
        )
        assertNull(SystemValues.current(ValueTable.Global, "null\n"))
        assertEquals("0.5", SystemValues.current(ValueTable.Global, "0.5\r\n"))
        assertEquals("", SystemValues.current(ValueTable.Props, "\n"), "an unset property is empty")
        assertEquals(false, SystemValues.editable(ValueTable.Props, "ro.build.type"))
        assertEquals(true, SystemValues.editable(ValueTable.Props, "debug.layout"))
        assertEquals(true, SystemValues.editable(ValueTable.Global, "ro.anything"))
    }

    @Test
    fun aRefusedFlagSaysWhyNotWhereItWasThrown() {
        val denied = File(fixtures, "device-config-put-denied-api36.txt").readText()
        assertEquals(
            "Permission denial for flag 'runtime_native_boot/droidective_probe'; allowlist " +
                "permission granted, but must add flag to the allowlist",
            Parse.failure("", denied),
        )
        assertEquals(
            "Failed to set property 'ro.x' to '1'.",
            Parse.failure("Failed to set property 'ro.x' to '1'.\nSee dmesg for error reason.\n"),
        )
        assertNull(Parse.failure("", "\n"))
        assertEquals(
            "This flag isn't on Android's allowlist for the shell user. Root can change it.",
            SystemValues.explain(ValueTable.Flags, Parse.failure("", denied).orEmpty()),
        )
        assertEquals("no", SystemValues.explain(ValueTable.Global, "no"))
    }

    @Test
    fun readsWhetherFlagsAreHeld() {
        assertEquals(false, SystemValues.flagsHeld("none\n"))
        assertEquals(true, SystemValues.flagsHeld("until_reboot\n"))
        assertEquals(true, SystemValues.flagsHeld("persistent"))
        assertNull(SystemValues.flagsHeld("Unknown command: get_sync_disabled_for_tests"))
        assertEquals(
            "cmd device_config set_sync_disabled_for_tests until_reboot",
            SystemValues.holdFlags.line,
        )
    }

    @Test
    fun anUndoPutsTheOldValueBackOrDeletesWhatWasntThere() {
        val set = ValueChange(ValueTable.Global, "k", before = "1", after = "2", at = 5)
        assertEquals("settings put global 'k' '1'", SystemValues.undo(set).line)
        val added = set.copy(before = null)
        assertEquals("settings delete global 'k'", SystemValues.undo(added).line)
        val prop = ValueChange(ValueTable.Props, "debug.x", before = "", after = "1", at = 5)
        assertEquals("setprop 'debug.x' ''", SystemValues.undo(prop).line)
    }

    @Test
    fun changesKeepTheFirstBeforeAndSurviveARestart() {
        val changes = ValueChanges(dir)
        changes.record(ValueChange(ValueTable.Global, "k", before = "1", after = "2", at = 1))
        changes.record(ValueChange(ValueTable.Global, "k", before = "2", after = "3", at = 2))
        changes.record(ValueChange(ValueTable.Props, "p", before = null, after = "x", at = 3))
        val reloaded = ValueChanges(dir).all()
        assertEquals(listOf("p", "k"), reloaded.map { it.key })
        assertEquals("1", reloaded.last().before, "undo goes back to before the first change")
        assertEquals("3", reloaded.last().after)
        ValueChanges(dir).forget(reloaded.last())
        assertNull(ValueChanges(dir).all().firstOrNull { it.key == "k" })
    }

    @Test
    fun aChangeBackToTheStartLeavesNothingToUndo() {
        val changes = ValueChanges(dir)
        changes.record(ValueChange(ValueTable.Global, "added", before = null, after = "1", at = 1))
        changes.record(ValueChange(ValueTable.Global, "added", before = "1", after = null, at = 2))
        assertEquals(emptyList(), changes.all())
    }
}
