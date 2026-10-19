package com.droidective.platform.shell

import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Lines read from a stream something else writes: a process's output, or a pipe from the Shizuku
 * service. A reader thread blocks on the stream; when the collector stops, [Source.stop] ends the
 * writer and the stream closes, which ends the read. Lines queue up to the channel's buffer and
 * then hold the writer back.
 */
internal object LineStream {
    class Source(val input: InputStream, val stop: () -> Unit)

    fun lines(open: suspend () -> Source): Flow<String> = callbackFlow {
        val source = open()
        thread(isDaemon = true, name = "line-stream") {
            try {
                source.input.bufferedReader().forEachLine { line ->
                    // A failed send means the collector is gone; stop reading.
                    if (trySendBlocking(line).isFailure) throw StopReading()
                }
            } catch (_: StopReading) {
                // The collector stopped first.
            } catch (_: IOException) {
                // Stopping closed the stream under the reader, which is how a read ends early.
            } finally {
                channel.close()
            }
        }
        awaitClose {
            source.stop()
            runCatching { source.input.close() }
        }
    }

    /** Runs [argv] and streams its stdout and stderr together; collecting stops it. */
    fun process(argv: List<String>): Flow<String> = lines {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.outputStream.close()
        Source(process.inputStream) { process.destroy() }
    }

    private class StopReading : RuntimeException()
}
