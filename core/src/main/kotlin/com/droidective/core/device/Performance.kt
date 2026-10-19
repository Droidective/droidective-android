package com.droidective.core.device

import com.droidective.core.apps.AppActions
import com.droidective.core.apps.MemInfo
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote
import java.util.Locale

/** One `/proc/stat` line's jiffies: [core] is -1 for the whole CPU, else the core's number. */
data class CpuTimes(val core: Int, val total: Long, val idle: Long)

/** How busy one core, or the whole CPU for [core] -1, was between two samples, 0 to 100. */
data class CoreLoad(val core: Int, val percent: Double)

/** One temperature the thermal HAL reports, in °C, with its HAL [type]. */
data class Temperature(val name: String, val type: Int, val celsius: Double) {
    companion object {
        const val CPU = 0
        const val GPU = 1
        const val BATTERY = 2
        const val SKIN = 3
    }
}

/**
 * What `dumpsys thermalservice` says: Android's [status] (0 none, up to 6 shutdown) and the HAL's
 * current readings.
 */
data class Thermal(val status: Int?, val temperatures: List<Temperature>) {
    val battery: Double?
        get() = hottest(Temperature.BATTERY)

    val skin: Double?
        get() = hottest(Temperature.SKIN)

    val cpu: Double?
        get() = hottest(Temperature.CPU)

    val gpu: Double?
        get() = hottest(Temperature.GPU)

    val statusTitle: String?
        get() = status?.let { STATUSES.getOrNull(it) }

    private fun hottest(type: Int) =
        temperatures.filter { it.type == type }.maxOfOrNull { it.celsius }

    private companion object {
        val STATUSES =
            listOf("None", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown")
    }
}

/** The frames an app's UI drew, as `dumpsys gfxinfo <package>` counts them since its last reset. */
data class FrameStats(
    val total: Int,
    val janky: Int,
    val percentiles: Map<Int, Int>,
    val slowUiThread: Int?,
    val missedVsync: Int?,
    val highInputLatency: Int?,
    val slowDraw: Int?,
)

/** A process's CPU time so far, in clock ticks, from `/proc/<pid>/stat`. */
data class ProcessTimes(val pid: Int, val ticks: Long)

/** One row of `top`. [cpu] is the percent of one core, as top counts it. */
data class TopProcess(val pid: Int, val cpu: Double, val resident: String, val name: String)

/** Everything one tick of the Performance tool reads, before any rates are worked out. */
data class PerformanceSample(
    val cpu: List<CpuTimes>,
    val memTotalKb: Long?,
    val memAvailableKb: Long?,
    val thermal: Thermal?,
    val frames: FrameStats?,
    val app: ProcessTimes?,
)

/**
 * Reads the phone's load the way Android Studio's profilers do, as the shell user: `/proc/stat` for
 * the CPU, `/proc/meminfo`, the thermal HAL's temperatures, and for one app the frames its UI drew
 * and its CPU time. One tick is one command, so the samples line up.
 */
object Performance {
    private const val MARK = "@@droidective:"

    fun sample(packageName: String?): ShellCommand {
        val parts =
            mutableListOf(
                "echo ${MARK}stat",
                "cat /proc/stat",
                "echo ${MARK}meminfo",
                "cat /proc/meminfo",
                "echo ${MARK}thermal",
                "dumpsys thermalservice",
            )
        if (packageName != null) {
            require(AppActions.isPackageName(packageName)) {
                "\"$packageName\" isn’t a package name"
            }
            val pkg = shellQuote(packageName)
            parts += "echo ${MARK}gfx"
            parts += "dumpsys gfxinfo $pkg"
            parts += "echo ${MARK}pid"
            parts += "p=$(pidof $pkg) && cat /proc/\${p%% *}/stat"
        }
        return ShellCommand(parts.joinToString("; "))
    }

    /** Starts the app's frame counts again, so the percentiles describe what happens next. */
    fun resetFrames(packageName: String): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return ShellCommand.of("dumpsys", "gfxinfo", shellQuote(packageName), "reset")
    }

    /**
     * The busiest processes. Two frames a second apart, since top's first frame has nothing to
     * measure against and reads 0% for every process.
     */
    val top: ShellCommand =
        ShellCommand.of(
            "top",
            "-b",
            "-n",
            "2",
            "-d",
            "1",
            "-m",
            "12",
            "-s",
            "2",
            "-o",
            "PID,%CPU,RES,NAME",
        )

    fun parse(output: String): PerformanceSample {
        val sections = sections(output)
        val memory = sections["meminfo"]?.let(::memory)
        return PerformanceSample(
            cpu = sections["stat"]?.let(::cpuTimes).orEmpty(),
            memTotalKb = memory?.get("MemTotal"),
            memAvailableKb = memory?.get("MemAvailable"),
            thermal = sections["thermal"]?.let(::thermal),
            frames = sections["gfx"]?.let(::frames),
            app = sections["pid"]?.let(::processTimes),
        )
    }

    private fun sections(output: String): Map<String, String> {
        val found = mutableMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in Parse.lines(output)) {
            if (line.startsWith(MARK)) {
                current = StringBuilder().also { found[line.removePrefix(MARK).trim()] = it }
            } else {
                current?.append(line)?.append('\n')
            }
        }
        return found.mapValues { it.value.toString() }
    }

    fun cpuTimes(output: String): List<CpuTimes> =
        Parse.lines(output).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val label =
                fields.firstOrNull()?.takeIf { it.startsWith("cpu") } ?: return@mapNotNull null
            val core = label.removePrefix("cpu").let { if (it.isEmpty()) -1 else it.toIntOrNull() }
            val numbers = fields.drop(1).mapNotNull(String::toLongOrNull)
            // user nice system idle iowait irq softirq steal: idle time is idle plus iowait.
            if (core == null || numbers.size < 5) null
            else CpuTimes(core, total = numbers.take(8).sum(), idle = numbers[3] + numbers[4])
        }

    /** The share of each core's time spent working between [before] and [after]. */
    fun loads(before: List<CpuTimes>, after: List<CpuTimes>): List<CoreLoad> {
        val earlier = before.associateBy { it.core }
        return after
            .mapNotNull { now ->
                val then = earlier[now.core] ?: return@mapNotNull null
                val total = now.total - then.total
                val idle = now.idle - then.idle
                val busy = if (total > 0) (1 - idle.toDouble() / total) * 100 else 0.0
                CoreLoad(now.core, busy.coerceIn(0.0, 100.0))
            }
            .sortedBy { it.core }
    }

    private val memLine = Regex("""^(\w+):\s+(\d+) kB""")

    private fun memory(output: String): Map<String, Long> =
        Parse.lines(output)
            .mapNotNull { memLine.find(it.trim())?.destructured }
            .associate { (key, value) -> key to value.toLong() }

    private val temperature =
        Regex("""Temperature\{mValue=([-\d.]+), mType=(-?\d+), mName=([^,]+), mStatus=""")

    /** The HAL's current readings; Android's cached ones when the HAL section is missing. */
    fun thermal(output: String): Thermal {
        val lines = Parse.lines(output)
        val status =
            lines
                .firstNotNullOfOrNull { line ->
                    line.trim().removePrefix("Thermal Status:").takeIf { it != line.trim() }
                }
                ?.trim()
                ?.toIntOrNull()
        fun block(header: String): List<Temperature> {
            val start = lines.indexOfFirst { it.trim() == header }
            if (start < 0) return emptyList()
            return lines
                .drop(start + 1)
                .takeWhile { it.startsWith("\t") || it.startsWith(" ") }
                .mapNotNull { temperature.find(it)?.destructured }
                .mapNotNull { (value, type, name) ->
                    value.toDoubleOrNull()?.let { Temperature(name, type.toInt(), it) }
                }
        }
        val current = block("Current temperatures from HAL:")
        return Thermal(status, current.ifEmpty { block("Cached temperatures:") })
    }

    private val percentile = Regex("""^(\d+)th percentile: (\d+)ms""")

    /** The first process's counts, which is the app's main one; null when it isn't running. */
    fun frames(output: String): FrameStats? {
        val lines = Parse.lines(output).map { it.trim() }
        fun count(label: String) = lines.firstNotNullOfOrNull { line ->
            line
                .removePrefix("$label:")
                .takeIf { it != line }
                ?.trim()
                ?.substringBefore(' ')
                ?.toIntOrNull()
        }
        val total = count("Total frames rendered") ?: return null
        return FrameStats(
            total = total,
            janky = count("Janky frames") ?: 0,
            percentiles =
                lines
                    .mapNotNull { percentile.find(it)?.destructured }
                    .associate { (rank, ms) -> rank.toInt() to ms.toInt() }
                    .let { found -> found.filterKeys { it in listOf(50, 90, 95, 99) } },
            slowUiThread = count("Number Slow UI thread"),
            missedVsync = count("Number Missed Vsync"),
            highInputLatency = count("Number High input latency"),
            slowDraw = count("Number Slow issue draw commands"),
        )
    }

    /** utime plus stime, the 14th and 15th fields; the name in brackets can hold spaces. */
    fun processTimes(output: String): ProcessTimes? {
        val line = Parse.lines(output).firstOrNull { it.isNotBlank() } ?: return null
        val pid = line.substringBefore(' ').toIntOrNull() ?: return null
        val fields = line.substringAfterLast(')').trim().split(' ')
        val user = fields.getOrNull(UTIME)?.toLongOrNull() ?: return null
        val system = fields.getOrNull(UTIME + 1)?.toLongOrNull() ?: return null
        return ProcessTimes(pid, user + system)
    }

    /** The last frame of [top]'s output, without top itself. */
    fun topProcesses(output: String): List<TopProcess> {
        val lines = Parse.lines(output)
        val header = lines.indexOfLast { it.trim().startsWith("PID") }
        if (header < 0) return emptyList()
        return lines.drop(header + 1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 4)
            if (fields.size < 4) return@mapNotNull null
            val pid = fields[0].toIntOrNull() ?: return@mapNotNull null
            val cpu = fields[1].toDoubleOrNull() ?: return@mapNotNull null
            TopProcess(pid, cpu, fields[2], fields[3]).takeIf { it.name != "top" }
        }
    }

    /** Field 14, utime, counted from the state that follows the bracketed name. */
    private const val UTIME = 11
}

/**
 * One tick of the Performance tool, as rates worked out against the tick before: CPU load per core
 * and the app's share of the whole CPU, frames per second and the share of them that were janky. A
 * rate is null when there's nothing to compare against yet.
 */
data class PerformanceReading(
    val cores: List<CoreLoad>,
    val memUsedKb: Long?,
    val memTotalKb: Long?,
    val thermal: Thermal?,
    val frames: FrameStats?,
    val fps: Double?,
    val jankPercent: Double?,
    val appCpuPercent: Double?,
) {
    /** The whole CPU's load, the first of [cores]. */
    val cpuPercent: Double?
        get() = cores.firstOrNull { it.core == -1 }?.percent
}

/** Turns samples into readings, one after another. [reset] when the app being watched changes. */
class PerformanceTracker {
    private var previous: PerformanceSample? = null
    private var previousAt = 0L

    fun reset() {
        previous = null
    }

    fun next(sample: PerformanceSample, atMillis: Long): PerformanceReading {
        val before = previous
        val seconds = (atMillis - previousAt) / 1000.0
        previous = sample
        previousAt = atMillis
        val frames = sample.frames
        val earlierFrames = before?.frames?.takeIf { frames != null && frames.total >= it.total }
        val drawn = earlierFrames?.let { frames!!.total - it.total }
        val janky = earlierFrames?.let { frames!!.janky - it.janky }
        return PerformanceReading(
            cores = before?.let { Performance.loads(it.cpu, sample.cpu) }.orEmpty(),
            memUsedKb =
                sample.memTotalKb?.let { total -> sample.memAvailableKb?.let { total - it } },
            memTotalKb = sample.memTotalKb,
            thermal = sample.thermal,
            frames = frames,
            fps = drawn?.takeIf { seconds > 0 }?.let { it / seconds },
            jankPercent = drawn?.takeIf { it > 0 }?.let { janky!!.coerceAtLeast(0) * 100.0 / it },
            appCpuPercent = before?.let { appShare(it, sample) },
        )
    }

    /** The app's CPU time as a share of all the CPU time that passed, if it's the same process. */
    private fun appShare(before: PerformanceSample, after: PerformanceSample): Double? {
        val then = before.app ?: return null
        val now = after.app?.takeIf { it.pid == then.pid } ?: return null
        val total =
            (after.cpu.firstOrNull { it.core == -1 }?.total ?: return null) -
                (before.cpu.firstOrNull { it.core == -1 }?.total ?: return null)
        if (total <= 0) return null
        return ((now.ticks - then.ticks) * 100.0 / total).coerceIn(0.0, 100.0)
    }
}

/**
 * What the Performance overlay shows over other apps, line by line: the load and the frames of the
 * app in front, then memory and the hottest part, then which app it is and any throttling.
 */
object PerformanceHud {
    fun lines(reading: PerformanceReading?, app: String?): List<String> {
        if (reading == null) return listOfNotNull("Measuring…", app)
        val load =
            listOfNotNull(
                reading.cpuPercent?.let { "CPU ${percent(it)}" },
                reading.fps?.let { "%.0f fps".format(Locale.ROOT, it) },
                reading.jankPercent?.let { "${percent(it)} janky" },
            )
        val thermal = reading.thermal
        val heat =
            thermal?.cpu?.let { "CPU ${degrees(it)}" }
                ?: thermal?.skin?.let { "Skin ${degrees(it)}" }
                ?: thermal?.battery?.let { "Battery ${degrees(it)}" }
        val body = listOfNotNull(reading.memUsedKb?.let { "RAM ${MemInfo.size(it)}" }, heat)
        val throttling =
            thermal?.takeIf { (it.status ?: 0) > 0 }?.statusTitle?.let { "Throttling: $it" }
        return listOf(load.joinToString(" · "), body.joinToString(" · "), app, throttling)
            .filterNot { it.isNullOrBlank() }
            .map { it!! }
    }

    private fun percent(value: Double) = "%.0f%%".format(Locale.ROOT, value)

    private fun degrees(celsius: Double) = "%.0f °C".format(Locale.ROOT, celsius)
}
