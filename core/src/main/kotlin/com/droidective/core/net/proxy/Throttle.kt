package com.droidective.core.net.proxy

import java.io.OutputStream

/**
 * A slower network than the phone has, for seeing what an app does on one: its spinners, its
 * timeouts, the screen it shows while the answer is still on its way. Chrome DevTools' presets, so
 * a speed means the same here as in the browser.
 *
 * @property latencyMillis added before each request goes out.
 * @property downBytesPerSecond how fast answers reach the app; 0 is as fast as they come.
 * @property upBytesPerSecond how fast the app's requests reach the server; 0 is as fast as they go.
 */
data class Throttle(
    val latencyMillis: Long,
    val downBytesPerSecond: Long,
    val upBytesPerSecond: Long,
) {
    val isOff: Boolean
        get() = latencyMillis == 0L && downBytesPerSecond == 0L && upBytesPerSecond == 0L

    /** A named speed, as offered on screen. */
    data class Preset(val name: String, val throttle: Throttle)

    companion object {
        val NONE = Throttle(0, 0, 0)

        val presets: List<Preset> =
            listOf(
                Preset("Unthrottled", NONE),
                Preset("Fast 4G", Throttle(60, kilobits(9_000), kilobits(1_500))),
                Preset("Slow 4G", Throttle(150, kilobits(1_600), kilobits(750))),
                Preset("3G", Throttle(2_000, kilobits(400), kilobits(400))),
            )

        private fun kilobits(perSecond: Long): Long = perSecond * 1_000 / BITS

        private const val BITS = 8
    }
}

/**
 * Writes no faster than [bytesPerSecond], by sleeping between writes. Bytes are passed on in small
 * pieces, so a slow answer arrives as a trickle rather than in bursts with silences between.
 */
class Paced(
    private val out: OutputStream,
    private val bytesPerSecond: Long,
    private val nanos: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : OutputStream() {
    private val started = nanos()
    private var written = 0L
    private val piece = (bytesPerSecond / PIECES_PER_SECOND).coerceAtLeast(1).toInt()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var at = off
        val end = off + len
        while (at < end) {
            val size = minOf(piece, end - at)
            out.write(b, at, size)
            out.flush()
            written += size
            at += size
            val due = written * NANOS_PER_SECOND / bytesPerSecond
            val ahead = (due - (nanos() - started)) / NANOS_PER_MILLI
            if (ahead > 0) sleep(ahead)
        }
    }

    override fun flush() = out.flush()

    override fun close() = out.close()

    companion object {
        /** [out] paced to [bytesPerSecond], or [out] itself when that is no limit at all. */
        fun of(out: OutputStream, bytesPerSecond: Long): OutputStream =
            if (bytesPerSecond > 0) Paced(out, bytesPerSecond) else out

        private const val PIECES_PER_SECOND = 20
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
