package com.droidective.core.apps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MemInfoTest {
    private fun fixture(name: String) =
        File(System.getProperty("fixtures.dir"), "meminfo/$name").readText()

    @Test
    fun quotesThePackage() {
        assertEquals(
            "dumpsys meminfo 'com.android.settings'",
            MemInfo.command("com.android.settings").line,
        )
    }

    @Test
    fun sizesReadAsPeopleSayThem() {
        assertEquals("0 KB", MemInfo.size(0))
        assertEquals("1023 KB", MemInfo.size(1023))
        assertEquals("1.0 MB", MemInfo.size(1024))
        assertEquals("64.0 MB", MemInfo.size(65_493))
        assertEquals("1.00 GB", MemInfo.size(1024 * 1024))
    }

    @Test
    fun readsTheAppSummaryIntoItsColumns() {
        val settings = MemInfo.parse(fixture("settings.txt")).single()
        assertEquals("com.android.settings", settings.process)
        assertEquals(11367, settings.pid)
        assertEquals(65493, settings.totalPssKb)
        assertEquals(179744, settings.totalRssKb)
        assertEquals(
            listOf(
                MemoryRow("Java Heap", 4632, 24084),
                MemoryRow("Native Heap", 13148, 14056),
                MemoryRow("Code", 27512, 133960),
                MemoryRow("Stack", 536, 540),
                MemoryRow("Graphics", 0, 0),
                MemoryRow("Private Other", 3872, null),
                MemoryRow("System", 15793, null),
                MemoryRow("Unknown", null, 7104),
            ),
            settings.summary,
        )
    }

    @Test
    fun readsTheObjectCounts() {
        val objects = MemInfo.parse(fixture("settings.txt")).single().objects
        assertEquals(164, objects["Views"])
        assertEquals(1, objects["Activities"])
        assertEquals(10, objects["AppContexts"])
        assertEquals(0, objects["WebViews"])
        assertEquals(12, objects.size)
    }

    @Test
    fun everyRecordedProcessParses() {
        val gms = MemInfo.parse(fixture("gms.txt")).single()
        assertEquals("com.google.android.gms", gms.process)
        assertEquals(8, gms.summary.size)
        assertEquals(
            gms.totalPssKb,
            gms.summary.mapNotNull { it.pssKb }.sum(),
            "the summary's PSS adds up to the total",
        )
    }

    @Test
    fun anAppThatIsNotRunningHasNoProcesses() {
        assertEquals(emptyList(), MemInfo.parse(fixture("not-running.txt")))
        assertEquals(emptyList(), MemInfo.parse(""))
    }

    @Test
    fun crlfAndAMissingRssColumnStillRead() {
        val crlf = fixture("settings.txt").replace("\n", "\r\n")
        assertEquals(MemInfo.parse(fixture("settings.txt")), MemInfo.parse(crlf))

        val pssOnly =
            """
            ** MEMINFO in pid 42 [com.example.old] **
             App Summary
                                   Pss(KB)
                                    ------
                       Java Heap:     4632
                     Native Heap:    13148

                       TOTAL PSS:    17780
            """
                .trimIndent()
        val old = MemInfo.parse(pssOnly).single()
        assertEquals(
            listOf(MemoryRow("Java Heap", 4632, null), MemoryRow("Native Heap", 13148, null)),
            old.summary,
        )
        assertEquals(17780, old.totalPssKb)
        assertEquals(null, old.totalRssKb)
        assertEquals(emptyMap(), old.objects)
    }
}
