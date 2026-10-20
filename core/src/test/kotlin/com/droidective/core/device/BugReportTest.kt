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
    fun handsTheZipOverReadableUnderItsOwnName() {
        val folder = "/storage/emulated/0/Android/data/com.droidective.android/files/handover"
        assertEquals(
            "cp '/data/user_de/0/com.android.shell/files/bugreports/bugreport-x.zip' " +
                "'$folder/bugreport-x.zip' && chmod 644 '$folder/bugreport-x.zip'",
            BugReport.handOver(
                    "/data/user_de/0/com.android.shell/files/bugreports/bugreport-x.zip",
                    folder,
                )
                .line,
        )
        assertFailsWith<IllegalArgumentException> { BugReport.handOver("/etc/hosts", folder) }
        assertFailsWith<IllegalArgumentException> { BugReport.handOver("/a/../b.zip", folder) }
    }
}
