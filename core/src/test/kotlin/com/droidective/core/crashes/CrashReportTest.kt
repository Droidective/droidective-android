package com.droidective.core.crashes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashReportTest {
    /** The entries in one `dumpsys dropbox --print` fixture, as `Entry.getText` would return. */
    private fun entries(file: String): List<Pair<String, String>> {
        val dir = File(System.getProperty("fixtures.dir"), "dropbox")
        val chunks = File(dir, file).readText().split("========================================\n")
        return chunks.drop(1).map { chunk ->
            val dateLine = chunk.lineSequence().first()
            val tag = dateLine.split(' ')[2]
            tag to chunk.substringAfter('\n').trimEnd()
        }
    }

    private fun parseAll(file: String) =
        entries(file).map { (tag, text) -> assertNotNull(CrashReport.parse(tag, 0, text), text) }

    @Test
    fun readsAJavaCrash() {
        val crash = parseAll("data_app_crash.txt").first()
        assertEquals(CrashKind.Crash, crash.kind)
        assertEquals("com.droidective.android", crash.packageName)
        assertEquals("0.1.0", crash.versionName)
        assertEquals(3954, crash.pid)
        assertEquals(true, crash.foreground)
        assertTrue(
            crash.headline.startsWith(
                "java.lang.SecurityException: Settings key: <reduce_bright_colors_activated>"
            ),
            crash.headline,
        )
        assertTrue("at com.droidective.platform.AndroidSettingsStore.get" in crash.details)
        assertTrue(crash.details.trimEnd().endsWith("AndroidUiDispatcher@ef72170]"))
    }

    @Test
    fun readsASystemAppCrash() {
        val settings =
            parseAll("system_app_crash.txt").first { it.process == "com.android.settings" }
        assertEquals("com.android.settings", settings.packageName)
        assertEquals("16", settings.versionName)
        assertTrue("shell-induced crash" in settings.headline, settings.headline)
    }

    @Test
    fun readsANativeCrashBySignal() {
        val native = parseAll("data_app_native_crash.txt").single()
        assertEquals(CrashKind.NativeCrash, native.kind)
        assertEquals(
            "signal 11 (SIGSEGV), code 0 (SI_USER from pid 11329, uid 10216), fault addr --------",
            native.headline,
        )
        assertTrue("backtrace:" in native.details)
    }

    @Test
    fun readsAnAnrByItsReason() {
        val anr = parseAll("data_app_anr.txt").single()
        assertEquals(CrashKind.Anr, anr.kind)
        assertEquals(false, anr.foreground)
        assertTrue(anr.headline.startsWith("Input dispatching timed out ("), anr.headline)
    }

    @Test
    fun everyFixtureEntryHasAPackageAndAHeadline() {
        val all =
            listOf(
                    "data_app_crash.txt",
                    "system_app_crash.txt",
                    "data_app_native_crash.txt",
                    "data_app_anr.txt",
                )
                .flatMap(::parseAll)
        assertEquals(18, all.size)
        all.forEach {
            assertTrue(it.packageName.isNotBlank() && it.headline.isNotBlank(), it.toString())
        }
    }

    @Test
    fun anAbortMessageSaysMoreThanTheSignal() {
        val text =
            """
            Process: com.example.game
            Package: com.example.game v42 (4.2)

            *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            Abort message: 'FORTIFY: pthread_mutex_lock called on a destroyed mutex'
            signal 6 (SIGABRT), code -1 (SI_QUEUE), fault addr --------
            """
                .trimIndent()
        val native = assertNotNull(CrashReport.parse("data_app_native_crash", 0, text))
        assertEquals("FORTIFY: pthread_mutex_lock called on a destroyed mutex", native.headline)
        assertEquals("4.2", native.versionName)
    }

    @Test
    fun aCutOffOrBodilessEntryStillReads() {
        val headerOnly = "Process: com.example.app:remote\r\nPID: 77\r\nForeground: Maybe"
        val report = assertNotNull(CrashReport.parse("data_app_anr", 5, headerOnly))
        assertEquals("com.example.app", report.packageName)
        assertEquals("com.example.app:remote", report.process)
        assertEquals(77, report.pid)
        assertNull(report.foreground)
        assertEquals(CrashKind.Anr.title, report.headline)
        assertEquals("", report.details)
    }

    @Test
    fun aSharedProcessIsFiledUnderItsFirstPackage() {
        val text =
            """
            Process: com.google.process.gapps
            Package: com.google.android.gms v251 (25.1)
            Package: com.google.android.gsf v36 (16)


            java.lang.IllegalStateException: boom
            """
                .trimIndent()
        val crash = assertNotNull(CrashReport.parse("data_app_crash", 0, text))
        assertEquals("com.google.android.gms", crash.packageName)
        assertEquals("25.1", crash.versionName)
        // Two blank lines after the header still find the exception.
        assertEquals("java.lang.IllegalStateException: boom", crash.headline)
        assertEquals("java.lang.IllegalStateException: boom", crash.details)
    }

    @Test
    fun otherTagsAndEntriesWithoutAProcessAreNotCrashes() {
        assertNull(CrashReport.parse("system_server_wtf", 0, "Process: system_server\n\nboom"))
        assertNull(CrashReport.parse("data_app_crash", 0, ""))
        assertNull(CrashReport.parse("data_app_crash", 0, "PID: 4\n\njava.lang.Error"))
    }
}
