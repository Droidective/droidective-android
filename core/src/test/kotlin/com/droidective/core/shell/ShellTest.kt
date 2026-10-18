package com.droidective.core.shell

import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ShellTest {
    @Test
    fun quotesTheSameWayAsTheMacApp() {
        // The cases ADBKit's ParserTests pin, so both apps send identical command lines.
        assertEquals("'myapp://x?a=1&b=2'", shellQuote("myapp://x?a=1&b=2"))
        assertEquals("'/data/local/My File.txt'", shellQuote("/data/local/My File.txt"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("''", shellQuote(""))
    }

    @Test
    fun anyQuotedValueReachesTheCommandAsOneUnchangedWord() = runTest {
        // NUL can't travel through an argv at all, so it's the one character left out.
        checkAll(200, Arb.string(0..40).filter { '\u0000' !in it }) { value ->
            assertEquals(value, echoThroughSh(shellQuote(value)))
        }
    }

    @Test
    fun metacharactersStayInert() {
        assertEquals(
            "\$(reboot); `id` && rm -rf / | x",
            echoThroughSh(shellQuote("\$(reboot); `id` && rm -rf / | x")),
        )
    }

    @Test
    fun parsesSettingsGetIncludingUnsetAndCrlf() {
        assertEquals("1", Parse.settingValue("1\r\n"))
        assertEquals("0.5", Parse.settingValue("0.5\n"))
        assertNull(Parse.settingValue("null\n"))
        assertNull(Parse.settingValue(""))
    }

    @Test
    fun parsesNightModeOutput() {
        assertEquals("yes", Parse.nightMode("Night mode: yes\r\n"))
        assertEquals("auto", Parse.nightMode("Night mode: auto"))
        assertNull(Parse.nightMode("Unknown command"))
    }

    @Test
    fun parsesWmDensityWithAndWithoutAnOverride() {
        assertEquals(Parse.Density(420, null), Parse.wmDensity("Physical density: 420\n"))
        assertEquals(
            Parse.Density(420, 480),
            Parse.wmDensity("Physical density: 420\r\nOverride density: 480\r\n"),
        )
        assertNull(Parse.wmDensity("cmd: Can't find service: window"))
    }

    @Test
    fun comparesSettingsNumerically() {
        assertEquals(true, Parse.sameSettingValue("1", "1.0"))
        assertEquals(true, Parse.sameSettingValue("0.50", "0.5"))
        assertEquals(false, Parse.sameSettingValue("1", "0"))
        assertEquals(true, Parse.sameSettingValue("off", "off"))
        assertEquals(false, Parse.sameSettingValue(null, "0"))
    }

    private fun echoThroughSh(quoted: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", "printf %s $quoted").start()
        val out = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "sh did not exit" }
        return out
    }
}
