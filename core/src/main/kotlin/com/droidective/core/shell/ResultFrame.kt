package com.droidective.core.shell

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [ShellResult] as bytes, so it can cross a pipe rather than a binder reply. A reply shares a
 * buffer of about 1 MB, and strings cross it as UTF-16, so output a few hundred KB long fills it:
 * the flag list from `cmd device_config list` alone made a reply of 714 KB on an API 36 emulator.
 *
 * The frame is the exit code, then stdout and stderr, each as a byte count and its UTF-8 bytes.
 */
object ResultFrame {
    fun write(result: ShellResult, out: OutputStream) {
        val data = DataOutputStream(BufferedOutputStream(out))
        data.writeInt(result.exitCode)
        writeText(data, result.stdout)
        writeText(data, result.stderr)
        data.flush()
    }

    /** @throws IOException when the frame ends early or isn't one, such as a writer that died. */
    fun read(input: InputStream): ShellResult {
        val data = DataInputStream(BufferedInputStream(input))
        val exitCode = data.readInt()
        return ShellResult(exitCode, readText(data), readText(data))
    }

    private fun writeText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readInt()
        if (size !in 0..MAX_BYTES) throw IOException("A shell result can't be $size bytes")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Far past any real output, so only a corrupt frame reaches it, and fails before allocating.
     */
    private const val MAX_BYTES = 256 * 1024 * 1024
}
