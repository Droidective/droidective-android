package com.droidective.core.device

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against a real `bugreportz -p` run on an API 36 emulator (fixtures/bugreport/). */
class BugReportTest {
    private val run =
        File(System.getProperty("fixtures.dir"), "bugreport/emulator-api36.txt").readText()

    @Test
    fun readsARealRunToTheZipItWrote() {
        val lines = run.lines().mapNotNull(BugReport::parse)
        val done = lines.filterIsInstance<BugReportLine.Done>().single()
        assertTrue(done.path.startsWith("/data/user_de/0/com.android.shell/files/bugreports/"))
        assertTrue(done.path.endsWith(".zip"))
        assertEquals(
            "bugreport-sdk_gphone64_arm64-BE2A.250530.026.F3-2026-10-20-03-28-04.zip",
            BugReport.fileName(done.path),
        )
        // Android 16 counts to 5000, not 100, and keeps printing progress after OK.
        assertEquals(BugReportLine.Progress(20, 5000), lines.first())
        assertTrue(lines.last() is BugReportLine.Progress)
    }

    @Test
    fun readsFailuresAndIgnoresAnythingElse() {
        assertEquals(
            BugReportLine.Failed("Could not open socket"),
            BugReport.parse("FAIL:Could not open socket"),
        )
        assertEquals(BugReportLine.Failed("bugreportz gave no reason"), BugReport.parse("FAIL:"))
        assertNull(BugReport.parse("Bugreport is in progress"))
        assertNull(BugReport.parse("PROGRESS:5/0"))
        assertEquals(BugReportLine.Progress(100, 100), BugReport.parse("PROGRESS:120/100\r"))
    }

    @Test
    fun copiesTheZipToDownloadsUnderItsOwnName() {
        assertEquals(
            "cp '/data/user_de/0/com.android.shell/files/bugreports/bugreport-x.zip' " +
                "'/sdcard/Download/bugreport-x.zip'",
            BugReport.copyToDownloads(
                    "/data/user_de/0/com.android.shell/files/bugreports/bugreport-x.zip",
                    "/sdcard/Download",
                )
                .line,
        )
        assertFailsWith<IllegalArgumentException> {
            BugReport.copyToDownloads("/etc/hosts", "/sdcard/Download")
        }
        assertFailsWith<IllegalArgumentException> {
            BugReport.copyToDownloads("/a/../b.zip", "/sdcard/Download")
        }
    }
}
