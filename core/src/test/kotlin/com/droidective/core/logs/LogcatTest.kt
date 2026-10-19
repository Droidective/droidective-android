package com.droidective.core.logs

import com.droidective.core.shell.ShellCommand
import com.droidective.core.testing.FakeShell
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LogcatTest {
    /** `logcat -d -v threadtime -T 60` as the Mac's harness recorded it from an API 35 emulator. */
    private val recorded: List<String> by lazy {
        val json =
            Json.parseToJsonElement(
                File(System.getProperty("fixtures.dir"), "android-emulator.json").readText()
            )
        json.jsonObject
            .getValue("entries")
            .jsonArray
            .map { it.jsonObject }
            .first { entry ->
                entry.getValue("arguments").jsonArray.any { it.jsonPrimitive.content == "logcat" }
            }
            .getValue("stdout")
            .jsonPrimitive
            .content
            .split('\n')
            .filter { it.isNotEmpty() }
    }

    @Test
    fun readsRecordedThreadtimeLines() {
        val lines = recorded.map(Logcat::parse)
        assertEquals(
            recorded.size - 1,
            lines.count { it.level != null },
            "every line but the banner parses",
        )
        val zip = lines.first { it.tag == "ziparchive" }
        assertEquals("07-25 12:11:55.389", zip.time)
        assertEquals(11234, zip.pid)
        assertEquals(30504, zip.tid)
        assertEquals(LogLevel.Warn, zip.level)
        assertEquals(
            "Unable to open '/data/user_de/0/com.google.android.gms/app_chimera/m/00000010/" +
                "dl-MlkitBarcodeUi.optional_262434100400.dm': No such file or directory",
            zip.message,
        )
        assertEquals("artd", lines.first { it.tag.startsWith("art") }.tag, "padding is dropped")
    }

    @Test
    fun aLineThatIsNotThreadtimeKeepsItsText() {
        val banner = Logcat.parse("--------- beginning of main\r")
        assertNull(banner.level)
        assertNull(banner.pid)
        assertEquals("--------- beginning of main", banner.message)
        assertEquals(
            LogLevel.Error,
            Logcat.parse("10-19 10:00:00.000  1  2 E AndroidRuntime: boom").level,
        )
    }

    @Test
    fun theLevelFilterIsQuotedAgainstTheShell() {
        assertEquals("logcat -v threadtime -T 300", Logcat.command(LogcatFilters()).line)
        assertEquals(
            "logcat -v threadtime -T 50 --pid 4242 '*:W'",
            Logcat.command(LogcatFilters(tail = 50, minimum = LogLevel.Warn, pid = 4242)).line,
        )
    }

    @Test
    fun findsAnAppsPid() {
        assertEquals("pidof 'com.example.app'", Logcat.pidCommand("com.example.app").line)
        assertEquals(4242, Logcat.parsePid("4242 4310\r\n"))
        assertNull(Logcat.parsePid(""))
        assertNull(Logcat.parsePid("pidof: not found"))
    }

    @Test
    fun aStreamEmitsWhatTheCommandPrints() = runTest {
        val shell = FakeShell()
        shell.streamLines("logcat", listOf("a", "b"))
        assertEquals(listOf("a", "b"), shell.stream(ShellCommand("logcat")).toList())
    }

    @Test
    fun theBufferKeepsTheNewestLines() {
        val buffer = LogBuffer(capacity = 3)
        buffer.add((1..2).map { Logcat.parse("line $it") })
        buffer.add((3..5).map { Logcat.parse("line $it") })
        assertEquals(listOf("line 3", "line 4", "line 5"), buffer.snapshot().map { it.raw })
        buffer.clear()
        assertEquals(0, buffer.size)
    }

    @Test
    fun searchIgnoresCaseAndABlankQueryKeepsAll() {
        val line = Logcat.parse("10-19 10:00:00.000  1  2 E AndroidRuntime: FATAL EXCEPTION: main")
        assertEquals(true, LogBuffer.matches(line, " fatal exception "))
        assertEquals(true, LogBuffer.matches(line, ""))
        assertEquals(false, LogBuffer.matches(line, "ANR"))
    }
}
