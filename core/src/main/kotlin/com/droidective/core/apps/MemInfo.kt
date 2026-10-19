package com.droidective.core.apps

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote
import kotlin.math.abs

/** One row of `dumpsys meminfo`'s App Summary, in KB; a column it leaves blank is null. */
data class MemoryRow(val label: String, val pssKb: Long?, val rssKb: Long?)

/**
 * One process's memory, as `dumpsys meminfo` summarises it for people. Sizes are in KB.
 *
 * @property summary the App Summary rows in dumpsys's order: Java Heap, Native Heap, Code, Stack,
 *   Graphics, Private Other, System, Unknown.
 * @property objects the Objects counts by name: Activities, Views, AppContexts and the rest.
 */
data class ProcessMemory(
    val process: String,
    val pid: Int,
    val totalPssKb: Long?,
    val totalRssKb: Long?,
    val summary: List<MemoryRow>,
    val objects: Map<String, Long>,
)

/** `dumpsys meminfo <package>`: the app's main process, which needs the shell to read. */
object MemInfo {
    private val blockStart = Regex("""^\*\* MEMINFO in pid (\d+) \[(.+)\] \*\*$""")
    private val number = Regex("""\d+""")
    private val totalPss = Regex("""TOTAL PSS:\s+(\d+)""")
    private val totalRss = Regex("""TOTAL RSS:\s+(\d+)""")
    private val count = Regex("""([A-Za-z][A-Za-z ]*):\s+(\d+)""")

    /** "512 KB", "64.0 MB", "1.25 GB": a size in KB as people read it. */
    fun size(kb: Long): String =
        when {
            kb < KB_PER_MB -> "$kb KB"
            kb < KB_PER_MB * KB_PER_MB -> "%.1f MB".format(kb / KB_PER_MB.toDouble())
            else -> "%.2f GB".format(kb / (KB_PER_MB * KB_PER_MB).toDouble())
        }

    private const val KB_PER_MB = 1024L

    fun command(packageName: String): ShellCommand =
        ShellCommand.of("dumpsys", "meminfo", shellQuote(packageName))

    /** Each process block in [output]; empty when the app isn't running. */
    fun parse(output: String): List<ProcessMemory> {
        val lines = Parse.lines(output)
        val starts =
            lines.indices.mapNotNull { i ->
                blockStart.matchEntire(lines[i].trim())?.let { i to it }
            }
        return starts.mapIndexed { i, (start, match) ->
            val end = starts.getOrNull(i + 1)?.first ?: lines.size
            block(match, lines.subList(start, end))
        }
    }

    private fun block(start: MatchResult, lines: List<String>): ProcessMemory {
        val (pid, process) = start.destructured
        val text = lines.joinToString("\n")
        return ProcessMemory(
            process = process,
            pid = pid.toInt(),
            totalPssKb = totalPss.find(text)?.groupValues?.get(1)?.toLongOrNull(),
            totalRssKb = totalRss.find(text)?.groupValues?.get(1)?.toLongOrNull(),
            summary = summary(lines),
            objects = objects(lines),
        )
    }

    /**
     * The App Summary's rows. Its numbers sit right-aligned under the `Pss(KB)` and `Rss(KB)`
     * headings, and a row can fill either column alone, so each number goes to the heading whose
     * end it's nearest.
     */
    private fun summary(lines: List<String>): List<MemoryRow> {
        val heading = lines.indexOfFirst { it.trim() == "App Summary" }
        if (heading < 0) return emptyList()
        val columns = lines.getOrNull(heading + 1).orEmpty()
        val pssEnd = columns.indexOf("Pss(KB)").takeIf { it >= 0 }?.plus("Pss(KB)".length)
        val rssEnd = columns.indexOf("Rss(KB)").takeIf { it >= 0 }?.plus("Rss(KB)".length)
        return section(lines, heading + 3).mapNotNull { line ->
            val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            val label = line.substring(0, colon).trim()
            if (label.startsWith("TOTAL")) return@mapNotNull null
            var pss: Long? = null
            var rss: Long? = null
            number.findAll(line, colon).forEach { match ->
                val end = match.range.last + 1
                val nearerRss =
                    rssEnd != null && (pssEnd == null || abs(end - rssEnd) < abs(end - pssEnd))
                if (nearerRss) rss = match.value.toLong() else pss = match.value.toLong()
            }
            MemoryRow(label, pss, rss)
        }
    }

    private fun objects(lines: List<String>): Map<String, Long> {
        val heading = lines.indexOfFirst { it.trim() == "Objects" }
        if (heading < 0) return emptyMap()
        return section(lines, heading + 1)
            .flatMap { line ->
                count.findAll(line).map { it.groupValues[1].trim() to it.groupValues[2].toLong() }
            }
            .toMap()
    }

    /** The lines from [from] up to the next blank one. */
    private fun section(lines: List<String>, from: Int): List<String> =
        lines.drop(from).takeWhile { it.isNotBlank() }
}
