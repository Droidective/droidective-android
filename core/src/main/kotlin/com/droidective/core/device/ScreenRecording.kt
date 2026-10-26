package com.droidective.core.device

import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How a screen recording is made. [width] and [height] are null for the display's own size;
 * [limitSeconds] 0 records until it's stopped.
 */
data class RecordOptions(
    val bitRateMbps: Int = 8,
    val width: Int? = null,
    val height: Int? = null,
    val timestamps: Boolean = false,
    val limitSeconds: Int = 0,
) {
    init {
        require(bitRateMbps in 1..MAX_MBPS) { "A bit rate of $bitRateMbps Mbps is out of range" }
        require((width == null) == (height == null)) { "A size needs both sides" }
        require(width == null || (width > 0 && height!! > 0)) { "A size can't be empty" }
        require(limitSeconds >= 0) { "A time limit can't be negative" }
    }

    private companion object {
        const val MAX_MBPS = 100
    }
}

/**
 * Records the screen with Android's `screenrecord`, run by the shell. It stops cleanly only on
 * SIGINT, the Ctrl-C it waits for: SIGTERM kills it before it writes the MP4's index, and the file
 * then plays nowhere.
 */
object ScreenRecording {
    fun command(options: RecordOptions, path: String): ShellCommand {
        require(path.endsWith(".mp4") && ".." !in path) { "\"$path\" isn’t a recording's path" }
        val words = mutableListOf("screenrecord", "--bit-rate", "${options.bitRateMbps * MEGA}")
        if (options.width != null) words += listOf("--size", "${options.width}x${options.height}")
        if (options.timestamps) words += "--bugreport"
        words += listOf("--time-limit", "${options.limitSeconds}", shellQuote(path))
        return ShellCommand(words.joinToString(" "))
    }

    /** Ctrl-C for the recording at [pid], which makes it finish the file and exit. */
    fun stop(pid: Int): ShellCommand = ShellCommand("kill -INT $pid")

    /** Lets this app read the finished file, which the shell owns. */
    fun readable(path: String): ShellCommand {
        require(path.endsWith(".mp4") && ".." !in path) { "\"$path\" isn’t a recording's path" }
        return ShellCommand.of("chmod", "644", shellQuote(path))
    }

    /** `screen-2026-10-20-05-40-12.mp4`, in the phone's own time zone. */
    fun fileName(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "screen-" + NAME.withZone(zone).format(Instant.ofEpochMilli(atMillis)) + ".mp4"

    /** Whether [name] is one of [fileName]'s. */
    fun isRecording(name: String): Boolean = name.startsWith("screen-") && name.endsWith(".mp4")

    /**
     * Half of [width] by [height], each rounded down to a multiple of 16, the block size video
     * encoders want.
     */
    fun half(width: Int, height: Int): Pair<Int, Int> =
        (width / 2 / BLOCK * BLOCK) to (height / 2 / BLOCK * BLOCK)

    private val NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    private const val MEGA = 1_000_000
    private const val BLOCK = 16
}
