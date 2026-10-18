package com.droidective.platform.shell

import com.droidective.core.shell.ShellResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Exit code reported for a command killed at its timeout, as coreutils `timeout` does. */
internal const val TIMED_OUT_EXIT = 124

/**
 * Runs one process to completion. Blocking: call it on an IO thread. Both pipes are drained on
 * their own threads, so a chatty command can't fill one and deadlock.
 */
internal object ProcessExec {
    fun run(argv: List<String>, timeoutMillis: Long): ShellResult {
        val process = ProcessBuilder(argv).start()
        process.outputStream.close()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val readers = listOf(drain(process.inputStream, stdout), drain(process.errorStream, stderr))
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        readers.forEach { it.join(READER_GRACE_MILLIS) }
        val err = String(stderr.toByteArray(), Charsets.UTF_8)
        val out = String(stdout.toByteArray(), Charsets.UTF_8)
        return if (finished) ShellResult(process.exitValue(), out, err)
        else
            ShellResult(
                TIMED_OUT_EXIT,
                out,
                "Timed out after ${timeoutMillis} ms\n$err",
            )
    }

    private fun drain(input: InputStream, sink: ByteArrayOutputStream): Thread =
        thread(isDaemon = true, name = "process-drain") { input.use { it.copyTo(sink) } }

    private const val READER_GRACE_MILLIS = 1_000L
}
