package com.droidective.core.device

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against output from an API 36 emulator and a OnePlus tablet (fixtures/performance/). */
class PerformanceTest {
    private val dir = File(System.getProperty("fixtures.dir"), "performance")

    private fun fixture(name: String) = File(dir, name).readText()

    @Test
    fun readsEveryCoreAndTheWholeCpu() {
        val emulator = Performance.cpuTimes(fixture("stat-api36.txt"))
        assertEquals(listOf(-1, 0, 1, 2, 3), emulator.map { it.core })
        assertEquals(CpuTimes(-1, 42853377, 41731073), emulator.first())
        assertEquals(
            (-1..7).toList(),
            Performance.cpuTimes(fixture("stat-oneplus.txt")).map { it.core },
        )
    }

    @Test
    fun loadIsTheWorkingShareOfTheTimeBetweenTwoReads() {
        val before = listOf(CpuTimes(-1, 1000, 800), CpuTimes(0, 500, 400))
        val after = listOf(CpuTimes(-1, 1200, 850), CpuTimes(0, 600, 500), CpuTimes(1, 10, 0))
        assertEquals(
            listOf(CoreLoad(-1, 75.0), CoreLoad(0, 0.0)),
            Performance.loads(before, after),
        )
    }

    @Test
    fun readsTheHalsCurrentTemperatures() {
        val tablet = Performance.thermal(fixture("thermal-oneplus.txt"))
        assertEquals(0, tablet.status)
        assertEquals("None", tablet.statusTitle)
        assertEquals(27.0, tablet.cpu)
        assertEquals(25.9, tablet.gpu)
        assertEquals(24.2, tablet.battery)
        assertEquals(24.55748, tablet.skin, "the current reading, not the cached 37.4")
        val emulator = Performance.thermal(fixture("thermal-api36.txt"))
        assertEquals(30.2, emulator.battery)
        assertNull(emulator.cpu)
    }

    @Test
    fun fallsBackToCachedTemperaturesWithoutTheHal() {
        val thermal =
            Performance.thermal(
                "Thermal Status: 2\nCached temperatures:\n\t" +
                    "Temperature{mValue=41.5, mType=2, mName=battery, mStatus=1}\n" +
                    "HAL Ready: false\n"
            )
        assertEquals("Moderate", thermal.statusTitle)
        assertEquals(41.5, thermal.battery)
    }

    @Test
    fun readsAnAppsFramesSinceItsLastReset() {
        val frames = Performance.frames(fixture("gfxinfo-settings-api36.txt"))!!
        assertEquals(212, frames.total)
        assertEquals(66, frames.janky)
        assertEquals(mapOf(50 to 25, 90 to 101, 95 to 150, 99 to 350), frames.percentiles)
        assertEquals(41, frames.slowUiThread)
        assertEquals(29, frames.missedVsync)
        assertEquals(275, frames.highInputLatency)
        assertEquals(51, frames.slowDraw)
        assertNull(Performance.frames(fixture("gfxinfo-not-running-api36.txt")))
    }

    @Test
    fun readsAProcesssCpuTimeWhateverItsNameHolds() {
        assertEquals(
            ProcessTimes(15936, 219 + 405),
            Performance.processTimes(fixture("pid-stat-api36.txt")),
        )
        assertEquals(
            ProcessTimes(7, 30),
            Performance.processTimes("7 (a) b (c)) S 1 1 0 0 -1 0 0 0 0 0 10 20 0 0\n"),
        )
        assertNull(Performance.processTimes(""))
    }

    @Test
    fun readsTopsSecondFrameWithoutTopItself() {
        val top = Performance.topProcesses(fixture("top-api36.txt"))
        assertEquals(
            TopProcess(475, 2.0, "3.8M", "android.hardware.sensors-service.multihal"),
            top.first(),
        )
        assertEquals(7, top.size)
        assertTrue(top.none { it.name == "top" })
        assertEquals(TopProcess(668, 1.0, "256M", "system_server"), top[2])
    }

    @Test
    fun oneTickIsOneCommandWithTheAppQuoted() {
        assertEquals(
            "echo @@droidective:stat; cat /proc/stat; echo @@droidective:meminfo; " +
                "cat /proc/meminfo; echo @@droidective:thermal; dumpsys thermalservice",
            Performance.sample(null).line,
        )
        val line = Performance.sample("com.example.app").line
        assertTrue(
            line.endsWith(
                "echo @@droidective:gfx; dumpsys gfxinfo 'com.example.app'; " +
                    "echo @@droidective:pid; " +
                    "p=$(pidof 'com.example.app') && cat /proc/\${p%% *}/stat"
            ),
            line,
        )
        assertFailsWith<IllegalArgumentException> { Performance.sample("a;reboot") }
        assertEquals(
            "dumpsys gfxinfo 'com.example.app' reset",
            Performance.resetFrames("com.example.app").line,
        )
    }

    @Test
    fun aTickSplitsBackIntoItsSources() {
        val output =
            "@@droidective:stat\n" +
                fixture("stat-api36.txt") +
                "@@droidective:meminfo\n" +
                fixture("meminfo-api36.txt") +
                "@@droidective:thermal\n" +
                fixture("thermal-api36.txt") +
                "@@droidective:gfx\n" +
                fixture("gfxinfo-settings-api36.txt") +
                "@@droidective:pid\n" +
                fixture("pid-stat-api36.txt")
        val sample = Performance.parse(output)
        assertEquals(5, sample.cpu.size)
        assertEquals(2531868, sample.memTotalKb)
        assertEquals(740252, sample.memAvailableKb)
        assertEquals(30.1, sample.thermal?.skin)
        assertEquals(212, sample.frames?.total)
        assertEquals(15936, sample.app?.pid)
        assertNull(Performance.parse("@@droidective:stat\n" + fixture("stat-api36.txt")).frames)
    }

    @Test
    fun readingsAreRatesBetweenTicks() {
        val tracker = PerformanceTracker()
        fun sample(total: Long, frames: Int, janky: Int, ticks: Long, pid: Int = 9) =
            PerformanceSample(
                cpu = listOf(CpuTimes(-1, total, total / 2)),
                memTotalKb = 1000,
                memAvailableKb = 400,
                thermal = null,
                frames = FrameStats(frames, janky, emptyMap(), null, null, null, null),
                app = ProcessTimes(pid, ticks),
            )
        val first = tracker.next(sample(1000, 100, 10, 50), atMillis = 0)
        assertNull(first.fps)
        assertNull(first.appCpuPercent)
        assertEquals(600, first.memUsedKb)
        val second = tracker.next(sample(1400, 160, 25, 150), atMillis = 2000)
        assertEquals(30.0, second.fps)
        assertEquals(25.0, second.jankPercent)
        assertEquals(25.0, second.appCpuPercent)
        assertEquals(50.0, second.cpuPercent)
        val reset = tracker.next(sample(1800, 5, 0, 200), atMillis = 3000)
        assertNull(reset.fps, "counts that went down were reset, so there's no rate")
        val restarted = tracker.next(sample(2200, 10, 0, 1, pid = 10), atMillis = 4000)
        assertNull(restarted.appCpuPercent, "a new process isn't comparable with the old one")
        val idle = tracker.next(sample(2600, 10, 0, 1, pid = 10), atMillis = 5000)
        assertEquals(0.0, idle.fps)
        assertNull(idle.jankPercent, "no frames drawn, so no share of them was janky")
    }
}
