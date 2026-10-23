package com.droidective.core.net.h2

import com.droidective.core.net.h2.Http2Frame.Companion.ACK
import com.droidective.core.net.h2.Http2Frame.Companion.CONTINUATION
import com.droidective.core.net.h2.Http2Frame.Companion.DATA
import com.droidective.core.net.h2.Http2Frame.Companion.END_HEADERS
import com.droidective.core.net.h2.Http2Frame.Companion.END_STREAM
import com.droidective.core.net.h2.Http2Frame.Companion.GOAWAY
import com.droidective.core.net.h2.Http2Frame.Companion.HEADERS
import com.droidective.core.net.h2.Http2Frame.Companion.NO_ERROR
import com.droidective.core.net.h2.Http2Frame.Companion.PING
import com.droidective.core.net.h2.Http2Frame.Companion.PRIORITY
import com.droidective.core.net.h2.Http2Frame.Companion.PRIORITY_OCTETS
import com.droidective.core.net.h2.Http2Frame.Companion.PUSH_PROMISE
import com.droidective.core.net.h2.Http2Frame.Companion.RST_STREAM
import com.droidective.core.net.h2.Http2Frame.Companion.SETTINGS
import com.droidective.core.net.h2.Http2Frame.Companion.WINDOW_UPDATE
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.HttpBody
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What a server said to one request: its fields, its body as far as it is kept, its trailers. */
class Http2Answer(val fields: List<Header>, val body: CapturedBody, val trailers: List<Header>)

/**
 * The client end of an HTTP/2 connection already open — TLS with `h2` agreed, or cleartext to a
 * server that speaks HTTP/2 from the first byte — for one request at a time: a call an app made,
 * sent again, or a question put to a server.
 *
 * It does what a client must and no more: the preface and its settings, the server's settings
 * acknowledged and its pings answered, flow control both ways. Streams go 1, 3, 5…, each to its end
 * before the next, and nothing is pushed, since it asks for nothing to be.
 */
class Http2Client(
    input: InputStream,
    output: OutputStream,
    private val keep: Int = HttpBody.MAX_CAPTURE,
) {
    private val input = DataInputStream(input.buffered())
    private val output = BufferedOutputStream(output)
    private val decoder = HpackDecoder()
    private var opened = false
    private var next = 1

    /** Why the connection can carry no more streams, once it can't. */
    private var gone: String? = null

    /** What the server lets this send: on the connection, and on each stream as it opens. */
    private var window = DEFAULT_WINDOW
    private var streamWindow = DEFAULT_WINDOW
    private var maxFrame = DEFAULT_MAX_FRAME.toInt()

    /** A smaller table the server asked for, which the next block must say it heard. */
    private var tableSize: Int? = null

    /**
     * Sends [fields], pseudo-headers first, and [body] on a stream of its own, and reads what comes
     * back to the end of it.
     *
     * @throws IOException when the connection fails or closes, the server resets the stream or goes
     *   away before reaching it, or what the server sends stops making sense as HTTP/2.
     */
    fun send(fields: List<Header>, body: ByteArray): Http2Answer {
        gone?.let { throw IOException(it) }
        val call = Call(next, body, keep)
        next += 2
        try {
            exchange(call, fields)
        } catch (e: StreamReset) {
            throw e
        } catch (e: Http2Exception) {
            gone = e.message
            throw IOException(e.message, e)
        } catch (e: IOException) {
            gone = e.message ?: CLOSED
            throw e
        }
        return Http2Answer(checkNotNull(call.fields), call.kept.body(), call.trailers)
    }

    private fun exchange(call: Call, fields: List<Header>) {
        if (!opened) open()
        val block = HpackEncoder.encode(fields, tableSize)
        tableSize = null
        headers(call.stream, block, endStream = call.body.isEmpty())
        call.window = streamWindow
        while (!call.done) {
            upload(call)
            if (!call.done) frame(read(), call)
        }
        // Answered before it had all of the body: the rest is no longer wanted.
        if (call.sent < call.body.size) {
            write(RST_STREAM, 0, call.stream, int32(NO_ERROR))
            output.flush()
        }
    }

    private fun open() {
        output.write(FrameCutter.PREFACE)
        val settings = ByteArrayOutputStream()
        setting(settings, ENABLE_PUSH, 0)
        setting(settings, INITIAL_WINDOW_SIZE, RECEIVE_WINDOW)
        write(SETTINGS, 0, 0, settings.toByteArray())
        write(WINDOW_UPDATE, 0, 0, int32(RECEIVE_WINDOW - DEFAULT_WINDOW.toInt()))
        output.flush()
        opened = true
    }

    /** As much of [call]'s body as the server's windows let through now. */
    private fun upload(call: Call) {
        while (call.sent < call.body.size) {
            val room = minOf(window, call.window, maxFrame.toLong()).toInt()
            if (room <= 0) break
            val size = minOf(room, call.body.size - call.sent)
            val end = call.sent + size == call.body.size
            write(DATA, if (end) END_STREAM else 0, call.stream, call.body, call.sent, size)
            call.sent += size
            window -= size
            call.window -= size
        }
        output.flush()
    }

    private fun frame(frame: Http2Frame, call: Call) {
        if (call.block != null && frame.type != CONTINUATION) {
            throw Http2Exception("a header block broken off part-way")
        }
        when (frame.type) {
            DATA -> data(frame, call)
            HEADERS -> headers(frame, call)
            CONTINUATION -> continuation(frame, call)
            RST_STREAM -> if (frame.stream == call.stream) reset(frame, call)
            else -> connection(frame, call)
        }
    }

    /** The frames about the connection rather than a stream; types not yet invented go unread. */
    private fun connection(frame: Http2Frame, call: Call) {
        when (frame.type) {
            SETTINGS -> settings(frame, call)
            WINDOW_UPDATE -> windowUpdate(frame, call)
            GOAWAY -> goAway(frame, call)
            PING -> if (!frame.has(ACK)) pong(frame)
            PUSH_PROMISE -> throw Http2Exception("a push, which this asked not to be sent")
        }
    }

    private fun data(frame: Http2Frame, call: Call) {
        val size = frame.payload.size
        // Handed back at once, padding and all, so a long answer never waits on this end.
        if (size > 0) {
            write(WINDOW_UPDATE, 0, 0, int32(size))
            if (frame.stream == call.stream && !frame.has(END_STREAM)) {
                write(WINDOW_UPDATE, 0, call.stream, int32(size))
            }
            output.flush()
        }
        if (frame.stream != call.stream) return
        if (call.fields == null) throw Http2Exception("a body before any answer")
        val (from, to) = frame.unpadded()
        call.kept.add(frame.payload, from, to)
        if (frame.has(END_STREAM)) call.end()
    }

    private fun headers(frame: Http2Frame, call: Call) {
        var (from, to) = frame.unpadded()
        if (frame.has(PRIORITY)) from += PRIORITY_OCTETS
        if (from > to) throw Http2Exception("a HEADERS frame shorter than its own priority")
        val block = Block(frame.stream, frame.has(END_STREAM))
        block.bytes.write(frame.payload, from, to - from)
        call.block = block
        if (frame.has(END_HEADERS)) fields(call)
    }

    private fun continuation(frame: Http2Frame, call: Call) {
        val block = call.block ?: throw Http2Exception("a CONTINUATION that continues nothing")
        if (block.stream != frame.stream) throw Http2Exception("a header block across two streams")
        block.bytes.write(frame.payload)
        if (frame.has(END_HEADERS)) fields(call)
    }

    private fun fields(call: Call) {
        val block = checkNotNull(call.block)
        call.block = null
        // Every block is read, whichever stream it is on: the table moves with each one.
        val fields = decoder.decode(block.bytes.toByteArray())
        if (block.stream != call.stream) return
        if (call.fields == null) {
            // A 1xx is not the answer; the answer is still to come.
            if (fields.firstOrNull { it.name == STATUS }?.value?.toIntOrNull() !in INFORMATIONAL) {
                call.fields = fields
            }
        } else {
            call.trailers = fields
        }
        if (block.endStream) call.end()
    }

    private fun reset(frame: Http2Frame, call: Call) {
        val code =
            octets(frame.payload, 0) ?: throw Http2Exception("a RST_STREAM of the wrong size")
        // A server that has answered may stop the rest of the upload this way; that is no failure.
        if (code == NO_ERROR.toLong() && call.fields != null) {
            call.end()
            return
        }
        throw StreamReset("the server reset it (${Http2Frame.error(code.toInt())})")
    }

    private fun settings(frame: Http2Frame, call: Call) {
        if (frame.has(ACK)) return
        if (frame.payload.size % SETTING_OCTETS != 0) {
            throw Http2Exception("SETTINGS that hold no whole number of settings")
        }
        for (at in frame.payload.indices step SETTING_OCTETS) {
            val id =
                (frame.payload[at].toInt() and BYTE shl BITS) or
                    (frame.payload[at + 1].toInt() and BYTE)
            val value = checkNotNull(octets(frame.payload, at + 2))
            setting(id, value, call)
        }
        write(SETTINGS, ACK, 0, ByteArray(0))
        output.flush()
    }

    private fun setting(id: Int, value: Long, call: Call) {
        when (id) {
            HEADER_TABLE_SIZE -> if (value < HpackDecoder.INITIAL_TABLE) tableSize = value.toInt()
            INITIAL_WINDOW_SIZE -> {
                if (value > MAX_WINDOW) throw Http2Exception("a window of $value, past 2^31 - 1")
                // A change applies to the streams already open, by the difference.
                call.window += value - streamWindow
                streamWindow = value
            }
            MAX_FRAME_SIZE -> {
                if (value !in DEFAULT_MAX_FRAME..MAX_FRAME_LIMIT) {
                    throw Http2Exception("a largest frame of $value bytes, which there can't be")
                }
                maxFrame = value.toInt()
            }
        }
    }

    private fun windowUpdate(frame: Http2Frame, call: Call) {
        val increment =
            octets(frame.payload, 0)?.and(MAX_WINDOW)
                ?: throw Http2Exception("a WINDOW_UPDATE of the wrong size")
        when (frame.stream) {
            0 -> window += increment
            call.stream -> call.window += increment
        }
    }

    private fun goAway(frame: Http2Frame, call: Call) {
        val last = octets(frame.payload, 0)?.and(MAX_WINDOW)
        val code = octets(frame.payload, LAST_STREAM_OCTETS)
        if (last == null || code == null) throw Http2Exception("a GOAWAY too short to say why")
        val why = "the server closed the connection (${Http2Frame.error(code.toInt())})"
        gone = why
        // It still finishes the streams it took; one past them it never will.
        if (call.stream > last) throw IOException(why)
    }

    private fun pong(frame: Http2Frame) {
        write(PING, ACK, 0, frame.payload)
        output.flush()
    }

    private fun read(): Http2Frame {
        val header = ByteArray(FRAME_HEADER)
        try {
            input.readFully(header)
        } catch (_: EOFException) {
            throw IOException(CLOSED)
        }
        val length =
            (header[0].toInt() and BYTE shl 16) or
                (header[1].toInt() and BYTE shl BITS) or
                (header[2].toInt() and BYTE)
        if (length > DEFAULT_MAX_FRAME) {
            throw Http2Exception("a frame of $length bytes, past the $DEFAULT_MAX_FRAME allowed")
        }
        val payload = ByteArray(length)
        try {
            input.readFully(payload)
        } catch (_: EOFException) {
            throw IOException(CLOSED)
        }
        val stream = (checkNotNull(octets(header, STREAM_AT)) and MAX_WINDOW).toInt()
        return Http2Frame(header[3].toInt() and BYTE, header[4].toInt() and BYTE, stream, payload)
    }

    /** A block too big for one frame goes on in CONTINUATIONs. */
    private fun headers(stream: Int, block: ByteArray, endStream: Boolean) {
        var at = 0
        do {
            val size = minOf(maxFrame, block.size - at)
            val first = at == 0
            var flags = if (at + size == block.size) END_HEADERS else 0
            if (first && endStream) flags = flags or END_STREAM
            write(if (first) HEADERS else CONTINUATION, flags, stream, block, at, size)
            at += size
        } while (at < block.size)
        output.flush()
    }

    private fun write(
        type: Int,
        flags: Int,
        stream: Int,
        payload: ByteArray,
        from: Int = 0,
        size: Int = payload.size,
    ) {
        output.write(size ushr 16)
        output.write(size ushr BITS)
        output.write(size)
        output.write(type)
        output.write(flags)
        output.write(int32(stream))
        output.write(payload, from, size)
    }

    private class Call(val stream: Int, val body: ByteArray, keep: Int) {
        val kept = Kept(keep)
        var sent = 0
        var window = 0L
        var fields: List<Header>? = null
        var trailers: List<Header> = emptyList()
        var block: Block? = null
        var done = false
            private set

        fun end() {
            if (fields == null) throw Http2Exception("the stream ended with no answer")
            done = true
        }
    }

    private class Block(val stream: Int, val endStream: Boolean) {
        val bytes = ByteArrayOutputStream()
    }

    /** The server ended this stream; the connection can carry another. */
    private class StreamReset(message: String) : IOException(message)

    private companion object {
        const val HEADER_TABLE_SIZE = 0x1
        const val ENABLE_PUSH = 0x2
        const val INITIAL_WINDOW_SIZE = 0x4
        const val MAX_FRAME_SIZE = 0x5

        const val DEFAULT_WINDOW = 65_535L
        const val DEFAULT_MAX_FRAME = 16_384L
        const val MAX_FRAME_LIMIT = 16_777_215L
        const val MAX_WINDOW = 0x7fff_ffffL

        /** What this lets a server send before it hands any back: 16 MB, past most answers. */
        const val RECEIVE_WINDOW = 1 shl 24

        const val FRAME_HEADER = 9
        const val STREAM_AT = 5
        const val SETTING_OCTETS = 6
        const val LAST_STREAM_OCTETS = 4
        const val BYTE = 0xff
        const val BITS = 8
        const val STATUS = ":status"
        const val CLOSED = "the connection closed before the answer finished"
        val INFORMATIONAL = 100..199

        fun setting(out: ByteArrayOutputStream, id: Int, value: Int) {
            out.write(id ushr BITS)
            out.write(id)
            out.write(int32(value))
        }

        fun int32(value: Int): ByteArray =
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr BITS).toByte(),
                value.toByte(),
            )

        /** The four octets at [at] as an unsigned number, or null when there aren't four. */
        fun octets(bytes: ByteArray, at: Int): Long? {
            if (bytes.size < at + 4) return null
            var value = 0L
            for (index in at until at + 4) value =
                (value shl BITS) or (bytes[index].toLong() and 0xff)
            return value
        }
    }
}
