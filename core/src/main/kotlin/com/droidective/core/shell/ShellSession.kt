package com.droidective.core.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * One long-lived shell, such as `su`, that runs command after command, so whatever grants it is
 * asked once. Magisk and KernelSU grant per `su` session and say so in a toast each time: started
 * per command, a phone running the proxy or an automation in the background showed one every few
 * seconds.
 *
 * Each command runs as `sh -c` would run it, in a shell of its own with nothing on its stdin, so an
 * `exit` or a `cd` can't touch the session or the commands after it. What it prints is told apart
 * from the next by a marker unique to the session, written after it on both streams, and its exit
 * code with it. A command past its timeout ends the session; the next one starts another.
 *
 * Blocking: call [run] on an IO thread. One command at a time.
 *
 * @param argv the shell to start, such as `listOf("su")`; it reads commands from its stdin.
 */
class ShellSession(private val argv: List<String>, private val timeoutMillis: Long) {
    private var running: Running? = null

    /**
     * Runs [command] in the session, starting it first if there's none.
     *
     * @throws ShellUnavailableException when the shell won't start, or ends without answering: `su`
     *   that isn't there, or that was refused.
     */
    @Synchronized
    fun run(command: ShellCommand): ShellResult {
        val session = running?.takeIf { it.alive } ?: start()
        return try {
            session.run(command, timeoutMillis)
        } catch (e: IOException) {
            close()
            throw ShellUnavailableException("${argv.first()} stopped answering: ${e.message}", e)
        } finally {
            if (!session.alive) close()
        }
    }

    /** Ends the session, if one is running; the next command starts a new one. */
    @Synchronized
    fun close() {
        running?.stop()
        running = null
    }

    private fun start(): Running {
        val process =
            try {
                ProcessBuilder(argv).start()
            } catch (e: IOException) {
                throw ShellUnavailableException("${argv.first()} isn't available: ${e.message}", e)
            }
        return Running(process).also { running = it }
    }

    /** A started shell, its two streams read into buffers as they arrive. */
    private class Running(private val process: Process) {
        private val marker = "droidective-${UUID.randomUUID()}"
        private val out = Collected(process.inputStream)
        private val err = Collected(process.errorStream)
        private val stdin: OutputStream = process.outputStream

        /** False once it has timed out, or its process has ended. */
        @Volatile
        var alive = true
            private set

        fun run(command: ShellCommand, timeoutMillis: Long): ShellResult {
            stdin.write(script(command).toByteArray(Charsets.UTF_8))
            stdin.flush()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val stdout = out.until("\n$marker:", deadline, lineAfter = true)
            val stderr = err.until("\n$marker\n", deadline, lineAfter = false)
            if (stdout == null || stderr == null) {
                alive = false
                return ShellResult(
                    TIMED_OUT_EXIT,
                    stdout?.text.orEmpty(),
                    "Timed out after $timeoutMillis ms",
                )
            }
            val code = stdout.after.trim().toIntOrNull() ?: throw IOException("no exit code")
            return ShellResult(code, stdout.text, stderr.text)
        }

        /**
         * [command] run on its own, then on each stream a newline and the marker, the exit code
         * after it on stdout. The newline first means the marker starts a line however the output
         * ended, and taking it back off gives the output exactly as printed.
         */
        private fun script(command: ShellCommand): String =
            "sh -c ${shellQuote(command.line)} </dev/null; " +
                "printf '\\n%s:%s\\n' '$marker' \"\$?\"; " +
                "printf '\\n%s\\n' '$marker' >&2\n"

        fun stop() {
            alive = false
            try {
                stdin.close()
            } catch (_: IOException) {
                // Already gone.
            }
            process.destroy()
        }

        private inner class Collected(input: InputStream) {
            private val buffer = ByteArrayOutputStream()
            private val lock = ReentrantLock()
            private val arrived = lock.newCondition()
            private var ended = false

            init {
                thread(isDaemon = true, name = "shell-session") {
                    val chunk = ByteArray(CHUNK)
                    try {
                        input.use {
                            var read = it.read(chunk)
                            while (read >= 0) {
                                lock.withLock {
                                    buffer.write(chunk, 0, read)
                                    arrived.signalAll()
                                }
                                read = it.read(chunk)
                            }
                        }
                    } catch (_: IOException) {
                        // The process went; waiting reads see the end below.
                    }
                    lock.withLock {
                        ended = true
                        arrived.signalAll()
                    }
                }
            }

            /**
             * What arrived before [end], taken out of the buffer with it, and with [lineAfter] the
             * rest of the line after it; null at [deadline].
             *
             * @throws IOException when the stream ends first.
             */
            fun until(end: String, deadline: Long, lineAfter: Boolean): Taken? = lock.withLock {
                var found = taken(end, lineAfter)
                var left = deadline - System.nanoTime()
                while (found == null && !ended && left > 0) {
                    arrived.awaitNanos(left)
                    found = taken(end, lineAfter)
                    left = deadline - System.nanoTime()
                }
                if (found == null && ended) {
                    alive = false
                    throw IOException("the shell ended")
                }
                found
            }

            private fun taken(end: String, lineAfter: Boolean): Taken? {
                val text = buffer.toString(Charsets.UTF_8.name())
                val at = text.indexOf(end)
                if (at < 0) return null
                val from = at + end.length
                val stop = if (lineAfter) text.indexOf('\n', from) else from
                if (stop < 0) return null
                val rest = text.substring(if (lineAfter) stop + 1 else stop)
                buffer.reset()
                buffer.write(rest.toByteArray(Charsets.UTF_8))
                return Taken(text.substring(0, at), text.substring(from, stop))
            }
        }
    }

    /** What a stream said before its marker, and what followed the marker on its line. */
    private class Taken(val text: String, val after: String)

    companion object {
        /** Exit code reported for a command stopped at its timeout, as coreutils `timeout` does. */
        const val TIMED_OUT_EXIT = 124

        private const val CHUNK = 8_192
    }
}
