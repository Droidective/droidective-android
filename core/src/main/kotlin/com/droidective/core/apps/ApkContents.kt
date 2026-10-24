package com.droidective.core.apps

import java.io.File
import java.io.RandomAccessFile

/**
 * A file or folder inside an APK, sized the way it costs: [packed] is what it takes in the APK (and
 * so in a download), [unpacked] what it holds.
 *
 * @property files how many files it is: 1 for a file, every file under it for a folder.
 * @property stored a file kept uncompressed; false for a folder.
 */
data class ApkNode(
    val name: String,
    val path: String,
    val folder: Boolean,
    val packed: Long,
    val unpacked: Long,
    val files: Int,
    val stored: Boolean,
)

/** An APK's files as folders to browse, biggest first, from its zip's central directory. */
object ApkContents {
    /** @throws java.io.IOException when [file] isn't a readable zip. */
    fun read(file: File): List<ZipRecord> = RandomAccessFile(file, "r").use(ZipDirectory::entries)

    /** What sits directly in [folder] ("" for the top), biggest first. */
    fun list(entries: List<ZipRecord>, folder: String): List<ApkNode> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val inside = entries.filter { it.name.startsWith(prefix) && it.name.length > prefix.length }
        val (files, nested) = inside.partition { '/' !in it.name.substring(prefix.length) }
        val folders =
            nested
                .groupBy { it.name.substring(prefix.length).substringBefore('/') }
                .map { (name, under) ->
                    ApkNode(
                        name = name,
                        path = prefix + name,
                        folder = true,
                        packed = under.sumOf { it.packed },
                        unpacked = under.sumOf { it.size },
                        files = under.size,
                        stored = false,
                    )
                }
        val leaves = files.map { entry ->
            ApkNode(
                name = entry.name.substring(prefix.length),
                path = entry.name,
                folder = false,
                packed = entry.packed,
                unpacked = entry.size,
                files = 1,
                stored = entry.stored,
            )
        }
        return (folders + leaves).sortedWith(
            compareByDescending<ApkNode> { it.packed }.thenBy { it.name }
        )
    }

    /** [part]'s share of [whole] to a tenth of a percent, such as "38.2%" or "under 0.1%". */
    fun share(part: Long, whole: Long): String {
        if (part <= 0 || whole <= 0) return "0%"
        val tenths = (part * PER_MILLE + whole / 2) / whole
        return if (tenths == 0L) "under 0.1%" else "${tenths / 10}.${tenths % 10}%"
    }

    /** The APK's top, then each folder down to [folder]. */
    fun crumbs(top: String, folder: String): List<Crumb> {
        val parts = folder.split('/').filter { it.isNotEmpty() }
        return listOf(Crumb(top, "")) +
            parts.indices.map { Crumb(parts[it], parts.take(it + 1).joinToString("/")) }
    }

    private const val PER_MILLE = 1000
}
