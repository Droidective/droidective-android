package com.droidective.core.device

import kotlin.math.abs

/** Bytes per second down and up, from Android's cumulative traffic counters. */
data class Rate(val downPerSecond: Double, val upPerSecond: Double)

/**
 * Turns cumulative byte counters into a rate. The first sample has nothing to compare against, and
 * a counter that goes backwards (a network interface reset) starts over instead of reporting a huge
 * negative or wrapped rate. So does a gap longer than [maxGapMillis], such as a screen coming back
 * from the background, which would otherwise report minutes of traffic as one second's rate.
 */
class RateMeter(private val maxGapMillis: Long = 5_000) {
    private var last: Triple<Long, Long, Long>? = null

    fun sample(down: Long, up: Long, atMillis: Long): Rate? {
        val previous = last
        last = Triple(down, up, atMillis)
        if (previous == null) return null
        val (lastDown, lastUp, lastAt) = previous
        val seconds = (atMillis - lastAt) / 1_000.0
        if (seconds <= 0 || atMillis - lastAt > maxGapMillis) return null
        if (down < lastDown || up < lastUp) return null
        return Rate((down - lastDown) / seconds, (up - lastUp) / seconds)
    }

    companion object {
        /** "0 B/s", "980 B/s", "1.2 KB/s", "14 MB/s": two significant figures at most. */
        fun format(bytesPerSecond: Double): String {
            val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
            var value = abs(bytesPerSecond)
            var unit = 0
            while (value >= 1_000 && unit < units.lastIndex) {
                value /= 1_000
                unit++
            }
            val text =
                when {
                    unit == 0 || value >= 10 -> value.toLong().toString()
                    else -> "%.1f".format(java.util.Locale.ROOT, value)
                }
            return "$text ${units[unit]}"
        }
    }
}

/** What the bootloader reports about itself, from the properties the shell user can read. */
object Bootloader {
    /**
     * "Locked" or "Unlocked": Android Verified Boot's `ro.boot.vbmeta.device_state`, else the older
     * `ro.boot.flash.locked`. Null when the phone reports neither, as an app without a shell sees
     * it.
     */
    fun lock(props: Map<String, String>): String? {
        val state = props["ro.boot.vbmeta.device_state"]?.trim()
        val flash = props["ro.boot.flash.locked"]?.trim()
        return when {
            state == "locked" || (state.isNullOrEmpty() && flash == "1") -> "Locked"
            state == "unlocked" || (state.isNullOrEmpty() && flash == "0") -> "Unlocked"
            else -> null
        }
    }

    /** The bootloader's version, or null for the "unknown" most phones put in `ro.bootloader`. */
    fun version(reported: String?): String? =
        reported?.trim()?.takeUnless { it.isEmpty() || it.equals("unknown", ignoreCase = true) }
}

/** What the phone shows about root, gathered by the app at App level. */
data class RootEvidence(
    val suBinaries: List<String>,
    val managers: List<String>,
    val buildTags: String?,
    val debuggable: Boolean?,
    val verifiedBootState: String?,
)

enum class RootVerdict(val title: String) {
    Rooted("Rooted"),
    Unlocked("Bootloader unlocked, no root found"),
    NotRooted("Not rooted"),
}

/** Reads [RootEvidence] the way a person would: any su or root manager means rooted. */
object RootCheck {
    /** Package names of the root managers people actually use. */
    val managerPackages =
        mapOf(
            "com.topjohnwu.magisk" to "Magisk",
            "me.weishu.kernelsu" to "KernelSU",
            "me.bmax.apatch" to "APatch",
        )

    /** Where su usually lives. */
    val suPaths =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su")

    fun verdict(evidence: RootEvidence): RootVerdict =
        when {
            evidence.suBinaries.isNotEmpty() || evidence.managers.isNotEmpty() -> RootVerdict.Rooted
            evidence.verifiedBootState == "orange" -> RootVerdict.Unlocked
            else -> RootVerdict.NotRooted
        }

    /** One line per thing that pointed toward root, for the screen to list. */
    fun reasons(evidence: RootEvidence): List<String> = buildList {
        evidence.suBinaries.forEach { add("su at $it") }
        evidence.managers.forEach { add("$it is installed") }
        if (evidence.verifiedBootState == "orange")
            add("Verified boot is orange: the bootloader is unlocked")
        if (evidence.buildTags?.contains("test-keys") == true) add("Built with test keys")
        if (evidence.debuggable == true) add("ro.debuggable is 1")
    }
}
