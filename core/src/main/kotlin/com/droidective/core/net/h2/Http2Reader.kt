package com.droidective.core.net.h2

import com.droidective.core.net.h2.Http2Frame.Companion.CONTINUATION
import com.droidective.core.net.h2.Http2Frame.Companion.DATA
import com.droidective.core.net.h2.Http2Frame.Companion.END_HEADERS
import com.droidective.core.net.h2.Http2Frame.Companion.END_STREAM
import com.droidective.core.net.h2.Http2Frame.Companion.HEADERS
import com.droidective.core.net.h2.Http2Frame.Companion.NO_ERROR
import com.droidective.core.net.h2.Http2Frame.Companion.PRIORITY
import com.droidective.core.net.h2.Http2Frame.Companion.PRIORITY_OCTETS
import com.droidective.core.net.h2.Http2Frame.Companion.PUSH_PROMISE
import com.droidective.core.net.h2.Http2Frame.Companion.RST_STREAM
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.HttpBody
import java.io.ByteArrayOutputStream

/** One request and its answer on an HTTP/2 connection — one stream — as far as it has got. */
class Http2Exchange internal constructor(val stream: Int, keep: Int) {
    /** The request's fields as sent, pseudo-headers (`:method`, `:path`…) and all. */
    var request: List<Header> = emptyList()
        internal set

    /** The answer's fields, once a final one has come: a 1xx is not the answer. */
    var response: List<Header>? = null
        internal set

    /** What the server sent after the body — where gRPC says how a call went. */
    var trailers: List<Header> = emptyList()
        internal set

    /** Who ended it early and how, in words; null when it ran its course. */
    var reset: String? = null
        internal set

    /**
     * Why it was not seen to the end, when it wasn't: the connection closed, or stopped reading.
     */
    var cut: String? = null
        internal set

    internal val sent = Kept(keep)
    internal val answered = Kept(keep)
    internal var appEnded = false
    internal var serverEnded = false

    val requestBody: CapturedBody
        get() = sent.body()

    val responseBody: CapturedBody?
        get() = if (response == null) null else answered.body()
}

/** What a [Http2Reader] has to tell, as it reads. */
interface Http2Events {
    /** [exchange]'s request is in. */
    fun requested(exchange: Http2Exchange)

    /** [exchange]'s answer has begun: its status and headers are in, its body may not be. */
    fun answered(exchange: Http2Exchange)

    /**
     * [exchange] is over: both ends said all they had, one reset it, or it was [Http2Exchange.cut].
     */
    fun finished(exchange: Http2Exchange)

    /** It stopped reading the connection, [why]; the connection itself carries on. */
    fun stopped(why: String)
}

/**
 * An HTTP/2 connection read from the side, both directions at once, into one exchange a stream.
 *
 * Nothing it does reaches the connection: it is shown a copy of the bytes after they have gone on,
 * so it can give up the moment they stop making sense and the two ends never know. Each direction
 * arrives on its own thread, and a server's answer can be shown before the request it answers.
 */
class Http2Reader(private val events: Http2Events, private val keep: Int = HttpBody.MAX_CAPTURE) {
    private val app = Side(fromApp = true)
    private val server = Side(fromApp = false)
    private val open = LinkedHashMap<Int, Http2Exchange>()
    private val asked = HashSet<Int>()

    /**
     * The newest stream the app has been seen to open; one at or below it that isn't open is over.
     * Only the app's end moves it: the server's end may be showing the answer to a stream opened
     * after another whose request hasn't been shown yet.
     */
    private var newest = 0

    /** Why it stopped reading, once it has. */
    var stopped: String? = null
        private set

    @Synchronized fun fromApp(bytes: ByteArray, count: Int) = feed(app, bytes, count)

    @Synchronized fun fromServer(bytes: ByteArray, count: Int) = feed(server, bytes, count)

    /** The connection is over: whatever is still open ends here, without its end. */
    @Synchronized
    fun closed() {
        if (stopped == null) cutAll(CLOSED)
    }

    private fun feed(side: Side, bytes: ByteArray, count: Int) {
        if (stopped != null) return
        try {
            side.frames.feed(bytes, 0, count)
        } catch (e: Http2Exception) {
            stop(e.message ?: "what it sent stopped making sense")
        } catch (e: RuntimeException) {
            // A mistake here must not cost the connection, nor leave one direction read alone.
            stop("reading it went wrong (${e.javaClass.simpleName})")
        }
    }

    private fun stop(why: String) {
        stopped = why
        cutAll("stopped reading the connection: $why")
        events.stopped(why)
    }

    private fun cutAll(why: String) {
        for (exchange in open.values.toList()) {
            exchange.cut = why
            finish(exchange)
        }
    }

    private fun frame(side: Side, frame: Http2Frame) {
        if (side.block != null && frame.type != CONTINUATION) {
            throw Http2Exception("a header block broken off part-way")
        }
        when (frame.type) {
            DATA -> data(side, frame)
            HEADERS -> headers(side, frame)
            RST_STREAM -> reset(side, frame)
            PUSH_PROMISE -> promise(side, frame)
            CONTINUATION -> continuation(side, frame)
        // SETTINGS, PING, GOAWAY, WINDOW_UPDATE, PRIORITY, and types not yet invented: none
        // changes what was said, and the header table moves only with the blocks themselves.
        }
    }

    private fun data(side: Side, frame: Http2Frame) {
        val exchange = exchange(frame.stream, side) ?: return
        val (from, to) = frame.unpadded()
        (if (side.fromApp) exchange.sent else exchange.answered).add(frame.payload, from, to)
        if (frame.has(END_STREAM)) ended(exchange, side)
    }

    private fun headers(side: Side, frame: Http2Frame) {
        var (from, to) = frame.unpadded()
        if (frame.has(PRIORITY)) from += PRIORITY_OCTETS
        if (from > to) throw Http2Exception("a HEADERS frame shorter than its own priority")
        side.block = Block(frame.stream, frame.has(END_STREAM), promised = false)
        side.add(frame, from, to)
    }

    private fun promise(side: Side, frame: Http2Frame) {
        var (from, to) = frame.unpadded()
        from += PROMISED_OCTETS
        if (from > to) throw Http2Exception("a PUSH_PROMISE with no stream to promise")
        // Read so the table stays right; a pushed answer is not something the app asked for.
        side.block = Block(frame.stream, endStream = false, promised = true)
        side.add(frame, from, to)
    }

    private fun continuation(side: Side, frame: Http2Frame) {
        val block = side.block ?: throw Http2Exception("a CONTINUATION that continues nothing")
        if (block.stream != frame.stream) throw Http2Exception("a header block across two streams")
        side.add(frame, 0, frame.payload.size)
    }

    private fun fields(side: Side, block: Block, fields: List<Header>) {
        if (block.promised) return
        val exchange = exchange(block.stream, side) ?: return
        when {
            side.fromApp && exchange.request.isEmpty() -> {
                exchange.request = fields
                asked += exchange.stream
                events.requested(exchange)
            }
            // A second block from the app is its trailers, which a request almost never has.
            side.fromApp -> Unit
            exchange.response == null -> answer(exchange, fields)
            else -> exchange.trailers = fields
        }
        if (block.endStream) {
            ended(exchange, side)
        } else if (side.fromApp && exchange.appEnded && exchange.serverEnded) {
            finish(exchange)
        }
    }

    private fun answer(exchange: Http2Exchange, fields: List<Header>) {
        if (informational(fields)) return
        exchange.response = fields
        // One whose request isn't shown yet is shown answered when it is.
        if (exchange.stream in asked) events.answered(exchange)
    }

    private fun reset(side: Side, frame: Http2Frame) {
        val exchange = exchange(frame.stream, side) ?: return
        if (frame.payload.size != RST_OCTETS) throw Http2Exception("a RST_STREAM of the wrong size")
        val code =
            frame.payload.fold(0) { value, octet -> (value shl 8) or (octet.toInt() and 0xff) }
        // A server that has answered may reset to stop the rest of an upload; that is no error.
        if (code != NO_ERROR) {
            val who = if (side.fromApp) "the app" else "the server"
            exchange.reset = "$who reset it (${Http2Frame.error(code)})"
        }
        exchange.appEnded = true
        exchange.serverEnded = true
        finish(exchange)
    }

    private fun ended(exchange: Http2Exchange, side: Side) {
        if (side.fromApp) exchange.appEnded = true else exchange.serverEnded = true
        if (exchange.appEnded && exchange.serverEnded) finish(exchange)
    }

    /**
     * Ends [exchange] on record. One whose request hasn't been shown yet — the answer got here
     * first — waits for it, unless the whole connection is going.
     */
    private fun finish(exchange: Http2Exchange) {
        if (exchange.stream !in asked) {
            if (exchange.cut != null) open.remove(exchange.stream)
            return
        }
        open.remove(exchange.stream)
        asked -= exchange.stream
        events.finished(exchange)
    }

    /**
     * The exchange on [stream], opening it when this is the first anyone has said of it. The
     * server's end can be first: it was shown the answer before the app's end showed the request.
     */
    private fun exchange(stream: Int, side: Side): Http2Exchange? {
        open[stream]?.let {
            return it
        }
        // Even streams are the server's own, pushed; anything at or below the newest is over.
        if (stream % 2 == 0 || stream <= newest) return null
        if (side.fromApp) newest = stream
        return Http2Exchange(stream, keep).also { open[stream] = it }
    }

    private fun informational(fields: List<Header>): Boolean =
        fields.firstOrNull { it.name == STATUS }?.value?.toIntOrNull() in INFORMATIONAL

    /** A header block as it builds up over a HEADERS or PUSH_PROMISE and its CONTINUATIONs. */
    private class Block(val stream: Int, val endStream: Boolean, val promised: Boolean) {
        val bytes = ByteArrayOutputStream()
    }

    /** One direction: its frames, its header table, the block it is part-way through. */
    private inner class Side(val fromApp: Boolean) {
        val frames = FrameCutter(preface = fromApp) { frame(this, it) }
        val decoder = HpackDecoder()
        var block: Block? = null

        fun add(frame: Http2Frame, from: Int, to: Int) {
            val building = checkNotNull(block)
            building.bytes.write(frame.payload, from, to - from)
            if (!frame.has(END_HEADERS)) return
            block = null
            fields(this, building, decoder.decode(building.bytes.toByteArray()))
        }
    }

    private companion object {
        const val PROMISED_OCTETS = 4
        const val RST_OCTETS = 4
        const val STATUS = ":status"
        const val CLOSED = "the connection closed before it finished"
        val INFORMATIONAL = 100..199
    }
}

/** As much of a body as is kept, and how long it really was. */
internal class Kept(private val limit: Int) {
    private val bytes = ByteArrayOutputStream()
    private var length = 0L

    fun add(source: ByteArray, from: Int, to: Int) {
        length += to - from
        val room = limit - bytes.size()
        if (room > 0) bytes.write(source, from, minOf(room, to - from))
    }

    fun body(): CapturedBody =
        CapturedBody(bytes.toByteArray(), length, truncated = length > bytes.size())
}
