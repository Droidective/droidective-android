package com.droidective.core.apps

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/**
 * One job an app has scheduled with JobScheduler, as `dumpsys jobscheduler <package>` lists it.
 * WorkManager's work rides on these, under the `androidx.work.systemjobscheduler` namespace.
 *
 * @property namespace the job's namespace (Android 14+), or null for the default one.
 * @property tag what Android traces it as; WorkManager puts the worker's name here.
 * @property waitingFor the constraints not met yet, such as TIMING_DELAY or CHARGING.
 */
data class ScheduledJob(
    val id: Int,
    val namespace: String?,
    val service: String,
    val tag: String?,
    val required: List<String>,
    val waitingFor: List<String>,
    val ready: Boolean,
    val runsAt: String?,
)

/** An app's scheduled jobs, read and run as the shell user. */
object Jobs {
    fun list(packageName: String): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return ShellCommand.of("dumpsys", "jobscheduler", shellQuote(packageName))
    }

    /**
     * Runs [job] now. Forced, so a job waiting on its delay or the network runs anyway, which is
     * what testing one needs; Android still skips constraints it can't fake, such as idle.
     */
    fun run(packageName: String, job: ScheduledJob): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        val words = mutableListOf("cmd", "jobscheduler", "run", "-f")
        job.namespace?.let { words += listOf("-n", shellQuote(it)) }
        words += listOf(shellQuote(packageName), job.id.toString())
        return ShellCommand(words.joinToString(" "))
    }

    private val header = Regex("""^ {2}JOB (?:(\S+?):)?#?u\d+a\d+/(\d+): \S+ (.+)$""")

    /** The jobs in the "Registered N jobs:" section of a `dumpsys jobscheduler <package>`. */
    fun parse(output: String): List<ScheduledJob> {
        val lines = Parse.lines(output)
        val start = lines.indexOfFirst { it.startsWith("Registered ") && it.endsWith(" jobs:") }
        if (start < 0) return emptyList()
        val jobs = mutableListOf<ScheduledJob>()
        var block = mutableListOf<String>()
        for (line in lines.drop(start + 1)) {
            if (line.isNotEmpty() && !line.startsWith("  ")) break
            if (header.matches(line) && block.isNotEmpty()) {
                job(block)?.let(jobs::add)
                block = mutableListOf()
            }
            block += line
        }
        job(block)?.let(jobs::add)
        return jobs
    }

    private fun job(block: List<String>): ScheduledJob? {
        val (namespace, id, service) =
            block.firstOrNull()?.let(header::find)?.destructured ?: return null
        fun field(name: String) = block.firstNotNullOfOrNull { line ->
            line.trim().takeIf { it.startsWith("$name:") }?.removePrefix("$name:")?.trim()
        }
        fun constraints(name: String) =
            field(name)?.substringBefore(" [")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        return ScheduledJob(
            id = id.toInt(),
            namespace = namespace.ifEmpty { null },
            service = service.removePrefix("@$namespace@"),
            tag = field("Trace tag"),
            required = constraints("Required constraints"),
            waitingFor = constraints("Unsatisfied constraints"),
            ready = field("Ready")?.startsWith("true") == true,
            runsAt = field("Run time")?.substringAfter("earliest=")?.substringBefore(",")?.trim(),
        )
    }
}

/** An app standby bucket, as `am get-standby-bucket` numbers it. */
enum class StandbyBucket(val number: Int, val shellName: String, val title: String) {
    Exempted(5, "exempted", "Exempted"),
    Active(10, "active", "Active"),
    WorkingSet(20, "working_set", "Working set"),
    Frequent(30, "frequent", "Frequent"),
    Rare(40, "rare", "Rare"),
    Restricted(45, "restricted", "Restricted"),
    Never(50, "never", "Never used");

    companion object {
        fun of(number: Int): StandbyBucket? = entries.firstOrNull { it.number == number }

        /** The ones `am set-standby-bucket` takes. */
        val settable = listOf(Active, WorkingSet, Frequent, Rare, Restricted)
    }
}

/**
 * Doze and app standby, the two things that hold an app's background work back, put into the state
 * a test needs and read back: the commands Android's own docs give for testing them.
 */
object Doze {
    val deepState: ShellCommand = ShellCommand.of("dumpsys", "deviceidle", "get", "deep")
    val lightState: ShellCommand = ShellCommand.of("dumpsys", "deviceidle", "get", "light")

    /** Into deep Doze now, as if the phone had sat still, unplugged and dark. */
    val forceDeep: ShellCommand = ShellCommand.of("dumpsys", "deviceidle", "force-idle", "deep")

    val forceLight: ShellCommand = ShellCommand.of("dumpsys", "deviceidle", "force-idle", "light")

    /** Back to Android deciding, which a test must always end with. */
    val unforce: ShellCommand = ShellCommand.of("dumpsys", "deviceidle", "unforce")

    /** One step through Doze's states, the way the phone would pass through them. */
    val step: ShellCommand = ShellCommand.of("dumpsys", "deviceidle", "step", "deep")

    /** Android only dozes on battery: this makes it believe it's unplugged. */
    val unplug: ShellCommand = ShellCommand.of("dumpsys", "battery", "unplug")

    /** The real battery again. */
    val resetBattery: ShellCommand = ShellCommand.of("dumpsys", "battery", "reset")

    fun getBucket(packageName: String): ShellCommand =
        forApp(packageName, "am", "get-standby-bucket")

    fun setBucket(packageName: String, bucket: StandbyBucket): ShellCommand {
        require(bucket in StandbyBucket.settable) { "${bucket.title} can't be set" }
        return ShellCommand(
            "${forApp(packageName, "am", "set-standby-bucket").line} ${bucket.shellName}"
        )
    }

    /** What `dumpsys deviceidle get` says, such as ACTIVE, IDLE or IDLE_MAINTENANCE. */
    fun state(output: String): String? =
        Parse.lines(output)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.all { c -> c.isUpperCase() || c == '_' } }

    fun bucket(output: String): StandbyBucket? =
        Parse.lines(output)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.toIntOrNull()
            ?.let(StandbyBucket::of)

    private fun forApp(packageName: String, vararg verb: String): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return ShellCommand.of(*verb, shellQuote(packageName))
    }
}

/**
 * One alarm an app has set with AlarmManager, as `dumpsys alarm` lists it.
 *
 * @property type RTC or ELAPSED, with _WAKEUP when it wakes a sleeping phone.
 * @property tag the alarm's action or listener tag, without AlarmManager's `*walarm*:` prefix.
 * @property inTime how long until it fires, as dumpsys counts it (`+1m52s585ms`).
 * @property exact whether it fires at its time rather than somewhere in a window.
 */
data class ScheduledAlarm(
    val type: String,
    val tag: String,
    val inTime: String,
    val exact: Boolean,
    val repeatMillis: Long,
    val exactReason: String?,
) {
    val wakesThePhone: Boolean
        get() = type.endsWith("_WAKEUP")
}

/** The alarms apps have pending, read as the shell user. */
object Alarms {
    val list: ShellCommand = ShellCommand.of("dumpsys", "alarm")

    private val header = Regex("""^\s+([A-Z_]+) #\d+: Alarm\{\S+ type \d+ .* (\S+)\}$""")

    /** [packageName]'s alarms, soonest first, as the "pending alarms" section lists them. */
    fun parse(output: String, packageName: String): List<ScheduledAlarm> {
        val lines = Parse.lines(output)
        val alarms = mutableListOf<ScheduledAlarm>()
        var index = 0
        while (index < lines.size) {
            val match = header.find(lines[index])
            index++
            if (match == null || match.groupValues[2] != packageName) continue
            val details =
                lines.drop(index).takeWhile { header.find(it) == null && it.startsWith("      ") }
            alarms += alarm(match.groupValues[1], details)
        }
        return alarms
    }

    private fun alarm(type: String, details: List<String>): ScheduledAlarm {
        fun field(name: String) = details.firstNotNullOfOrNull { line ->
            Regex("""(?:^|\s)$name=(\S+)""").find(line.trim())?.groupValues?.get(1)
        }
        val tag =
            details
                .firstOrNull { it.trim().startsWith("tag=") }
                ?.trim()
                ?.removePrefix("tag=")
                ?.substringAfter("alarm*:")
                .orEmpty()
        return ScheduledAlarm(
            type = type,
            tag = tag,
            inTime = field("whenElapsed").orEmpty(),
            exact = field("window") == "0",
            repeatMillis = field("repeatInterval")?.toLongOrNull() ?: 0,
            exactReason = field("exactAllowReason"),
        )
    }

    /**
     * `+1m52s585ms` as people say it: its two largest units that aren't zero, `1m 52s`, and "in"
     * goes in front of it, so a year or more reads as "more than a year away".
     */
    fun readable(inTime: String): String {
        if (inTime.startsWith("-")) return "overdue"
        val units =
            Regex("""(\d+)(d|h|ms|m|s)""").findAll(inTime).associate {
                it.groupValues[2] to it.groupValues[1].toLong()
            }
        // Some apps park an alarm years out rather than cancel it.
        if ((units["d"] ?: 0) >= DAYS_PER_YEAR) return "more than a year away"
        val parts =
            listOf("d", "h", "m", "s")
                .mapNotNull { unit -> units[unit]?.takeIf { it > 0 }?.let { "$it$unit" } }
                .take(2)
        return parts.ifEmpty { listOf("now") }.joinToString(" ")
    }

    private const val DAYS_PER_YEAR = 365
}
