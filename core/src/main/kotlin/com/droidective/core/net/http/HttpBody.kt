package com.droidective.core.net.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * What the proxy kept of a body.
 *
 * [bytes] is what there was room for, [wireLength] is how many bytes it really took on the
 * connection — framing included — and [truncated] says the two differ, so a reader is never shown a
 * clipped body as if it were whole.
 */
class CapturedBody(val bytes: ByteArray, val wireLength: Long, val truncated: Boolean) {
    val isEmpty: Boolean
        get() = wireLength == 0L

    companion object {
        val NONE = CapturedBody(ByteArray(0), 0, false)
    }
}

/**
 * Moving a body from one connection to the other while keeping a copy of it.
 *
 * The copy is bounded and the move is not: a forty-megabyte download is forwarded whole and
 * remembered only up to the limit, because the app being watched must behave exactly as it would
 * without the proxy in the way. That is the whole bargain of this tool — what it shows you is a
 * record of what happened, not something it made happen differently.
 */
object HttpBody {
    /** As much of a body as is worth holding in memory for one exchange. */
    const val MAX_CAPTURE = 2 * 1024 * 1024

    /**
     * Moves the body [framing] describes from [source] to [sink], keeping up to [limit] bytes.
     *
     * @param sink where the body goes; null reads it and throws it away, which is how a tunnel's
     *   leftovers are drained.
     */
    fun relay(
        framing: BodyFraming,
        source: InputStream,
        sink: OutputStream?,
        limit: Int = MAX_CAPTURE,
    ): CapturedBody {
        val record = Record(sink, limit)
        val wire =
            when (framing) {
                BodyFraming.Empty -> return CapturedBody.NONE
                is BodyFraming.Fixed -> copy(source, record, framing.length)
                BodyFraming.UntilClose -> copy(source, record, Long.MAX_VALUE)
                BodyFraming.Chunked -> chunked(source, record)
            }
        sink?.flush()
        return record.captured(wire)
    }

    /** Copies [length] bytes, or everything left when the length is unbounded. */
    private fun copy(source: InputStream, record: Record, length: Long): Long {
        val buffer = ByteArray(BUFFER)
        var moved = 0L
        while (moved < length) {
            val wanted = minOf((length - moved), buffer.size.toLong()).toInt()
            val read = source.read(buffer, 0, wanted)
            if (read == END) {
                // A stated length that the connection did not deliver is a broken message; a body
                // that runs until close ending is just the end of it.
                if (length != Long.MAX_VALUE)
                    throw IOException("the body stopped $moved of $length")
                break
            }
            record.write(buffer, read)
            moved += read
        }
        return moved
    }

    /**
     * Copies a chunked body, chunk by chunk, writing the framing back out as it goes so the far
     * side sees the same shape it would have seen. Only the payload is kept.
     */
    private fun chunked(source: InputStream, record: Record): Long {
        var wire = 0L
        while (true) {
            val line = readWireLine(source) ?: throw IOException("the body ended mid-chunk")
            wire += line.length + CRLF.length
            val size =
                line.substringBefore(';').trim().toLongOrNull(HEX)
                    ?: throw IOException("not a chunk size: \"$line\"")
            if (size < 0) throw IOException("a chunk cannot be $size bytes")
            record.frame(line + CRLF)
            if (size == 0L) return wire + trailers(source, record)
            wire += copy(source, record, size)
            endOfChunk(source)
            record.frame(CRLF)
            wire += CRLF.length
        }
    }

    /** The CRLF that closes a chunk, which has to be there and carries nothing. */
    private fun endOfChunk(source: InputStream) {
        val line = readWireLine(source) ?: throw IOException("a chunk ended with nothing after it")
        if (line.isNotEmpty()) throw IOException("a chunk ended with \"$line\"")
    }

    /** The headers a chunked body may end with, passed along as they came. */
    private fun trailers(source: InputStream, record: Record): Long {
        var wire = 0L
        while (true) {
            val line = readWireLine(source) ?: throw IOException("the trailers never ended")
            wire += line.length + CRLF.length
            record.frame(line + CRLF)
            if (line.isEmpty()) return wire
        }
    }

    /** Writes a body through to its destination while keeping the first [limit] bytes of it. */
    private class Record(private val sink: OutputStream?, private val limit: Int) {
        private val kept = ByteArrayOutputStream()
        private var decoded = 0L

        fun write(buffer: ByteArray, length: Int) {
            sink?.write(buffer, 0, length)
            decoded += length
            val room = limit - kept.size()
            if (room > 0) kept.write(buffer, 0, minOf(room, length))
        }

        /** Framing bytes: they go on to the far side but are not part of the body. */
        fun frame(text: String) {
            sink?.write(text.toByteArray(Charsets.ISO_8859_1))
        }

        fun captured(wire: Long) =
            CapturedBody(kept.toByteArray(), wire, truncated = decoded > kept.size())
    }

    private const val BUFFER = 16 * 1024
    private const val HEX = 16
    private const val END = -1
}
