package com.droidective.core.net.ws

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** One WebSocket message, as it crossed the proxy: who sent it, when, what kind, what it said. */
class SocketMessage(
    val fromApp: Boolean,
    val at: Long,
    val kind: Kind,
    /** What it said, unmasked and inflated, as far as it is kept. */
    val data: ByteArray,
    /** How long it was, whole and inflated. */
    val length: Long,
    val truncated: Boolean,
) {
    enum class Kind {
        Text,
        Binary,
        Close,
        Ping,
        Pong,
    }

    /** What a text message said; a character cut in two by the end of what is kept shows as �. */
    val text: String
        get() = data.decodeToString()

    /** The status a close gave, when it gave one. */
    val closeCode: Int?
        get() =
            if (kind == Kind.Close && data.size >= 2) {
                ((data[0].toInt() and BYTE) shl BITS) or (data[1].toInt() and BYTE)
            } else {
                null
            }

    private companion object {
        const val BYTE = 0xff
        const val BITS = 8
    }
}

/**
 * What the two ends agreed on in the `101`'s `Sec-WebSocket-Extensions`: whether messages are
 * compressed with permessage-deflate (RFC 7692), and whether either side starts each message afresh
 * rather than carrying its window over from the last.
 *
 * @property unknown an extension in use that this doesn't know, whose frames it can't tell apart
 *   from nonsense; null when there is none.
 */
data class Negotiated(
    val deflate: Boolean,
    val appResets: Boolean,
    val serverResets: Boolean,
    val unknown: String? = null,
) {
    val readable: Boolean
        get() = unknown == null

    companion object {
        val PLAIN = Negotiated(deflate = false, appResets = false, serverResets = false)

        /** What the `Sec-WebSocket-Extensions` values a server answered with agree to. */
        fun of(extensions: List<String>): Negotiated {
            var agreed = PLAIN
            for (extension in extensions.flatMap { it.split(',') }) {
                val parameters =
                    extension.split(';').map { it.trim().substringBefore('=').lowercase() }
                when (parameters.first()) {
                    "" -> Unit
                    DEFLATE ->
                        agreed =
                            agreed.copy(
                                deflate = true,
                                appResets = APP_RESETS in parameters,
                                serverResets = SERVER_RESETS in parameters,
                            )
                    else -> agreed = agreed.copy(unknown = agreed.unknown ?: parameters.first())
                }
            }
            return agreed
        }

        private const val DEFLATE = "permessage-deflate"
        private const val APP_RESETS = "client_no_context_takeover"
        private const val SERVER_RESETS = "server_no_context_takeover"
    }
}

/**
 * Reads RFC 6455 frames out of one direction of a WebSocket as its bytes cross the proxy, and hands
 * over each whole message: fragments put back together, the app's masking undone, permessage-
 * deflate inflated. It reads a copy and never writes; what crosses is not its business, so a stream
 * it can't make sense of just stops being read.
 *
 * @param keep how much of one message is held on to. Every compressed byte is inflated even past
 *   it, since the next message's window depends on this one's.
 */
class FrameReader(
    private val fromApp: Boolean,
    negotiated: Negotiated,
    private val clock: () -> Long,
    private val keep: Int = MAX_KEPT,
    private val onMessage: (SocketMessage) -> Unit,
) {
    private val inflater = if (negotiated.deflate) Inflater(true) else null
    private val resets = if (fromApp) negotiated.appResets else negotiated.serverResets
    private val inflated = ByteArray(INFLATE_CHUNK)

    /** Whether the stream stopped making sense, after which nothing more is read from it. */
    var broken: Boolean = !negotiated.readable
        private set

    private val header = ByteArray(MAX_HEADER)
    private var headerRead = 0
    private var headerNeeded = 2

    private var opcode = 0
    private var fin = false
    private var key: ByteArray? = null
    private var remaining = 0L
    private var position = 0L

    private var message: SocketMessage.Kind? = null
    private var compressed = false
    private val kept = ByteArrayOutputStream()
    private var length = 0L
    private val control = ByteArrayOutputStream()

    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        var at = offset
        val end = offset + count
        try {
            while (at < end && !broken) {
                at =
                    if (headerRead < headerNeeded) header(bytes, at, end)
                    else payload(bytes, at, end)
            }
        } catch (_: Unreadable) {
            broken = true
        } catch (_: DataFormatException) {
            broken = true
        }
    }

    private fun header(bytes: ByteArray, from: Int, end: Int): Int {
        val take = minOf(end - from, headerNeeded - headerRead)
        System.arraycopy(bytes, from, header, headerRead, take)
        headerRead += take
        if (headerRead == 2) headerNeeded = 2 + extendedLength() + if (masked()) MASK else 0
        if (headerRead == headerNeeded) begin()
        return from + take
    }

    private fun masked() = header[1].toInt() and MASK_BIT != 0

    private fun extendedLength(): Int =
        when (header[1].toInt() and LENGTH_BITS) {
            LENGTH_16 -> 2
            LENGTH_64 -> 8
            else -> 0
        }

    /** A frame's head is in: what it is, how long, whether it fits what came before it. */
    private fun begin() {
        val first = header[0].toInt()
        fin = first and FIN_BIT != 0
        opcode = first and OPCODE_BITS
        val rsv1 = first and RSV1_BIT != 0
        if (first and (RSV2_BIT or RSV3_BIT) != 0) throw Unreadable()
        remaining = payloadLength()
        key = if (masked()) header.copyOfRange(headerNeeded - MASK, headerNeeded) else null
        position = 0
        when {
            opcode >= CLOSE -> beginControl(rsv1)
            opcode == CONTINUATION -> if (message == null || rsv1) throw Unreadable()
            else -> beginMessage(rsv1)
        }
        if (remaining == 0L) end()
    }

    private fun payloadLength(): Long {
        val short = header[1].toInt() and LENGTH_BITS
        if (short < LENGTH_16) return short.toLong()
        var value = 0L
        for (index in 2 until 2 + extendedLength()) value =
            (value shl 8) or (header[index].toLong() and 0xff)
        if (value < 0) throw Unreadable()
        return value
    }

    private fun beginControl(rsv1: Boolean) {
        val known = opcode == CLOSE || opcode == PING || opcode == PONG
        if (!known || rsv1 || !fin || remaining > MAX_CONTROL) throw Unreadable()
        control.reset()
    }

    private fun beginMessage(rsv1: Boolean) {
        if (message != null) throw Unreadable()
        message =
            when (opcode) {
                TEXT -> SocketMessage.Kind.Text
                BINARY -> SocketMessage.Kind.Binary
                else -> throw Unreadable()
            }
        if (rsv1 && inflater == null) throw Unreadable()
        compressed = rsv1
        kept.reset()
        length = 0
    }

    private fun payload(bytes: ByteArray, from: Int, end: Int): Int {
        val take = minOf((end - from).toLong(), remaining).toInt()
        val chunk = bytes.copyOfRange(from, from + take)
        key?.let { mask ->
            for (index in chunk.indices) {
                val byte = mask[((position + index) % MASK).toInt()]
                chunk[index] = (chunk[index].toInt() xor byte.toInt()).toByte()
            }
        }
        position += take
        remaining -= take
        when {
            opcode >= CLOSE -> control.write(chunk)
            compressed -> inflate(chunk)
            else -> held(chunk, chunk.size)
        }
        if (remaining == 0L) end()
        return from + take
    }

    private fun held(bytes: ByteArray, count: Int) {
        length += count
        val room = keep - kept.size()
        if (room > 0) kept.write(bytes, 0, minOf(room, count))
    }

    private fun inflate(input: ByteArray) {
        val inflater = inflater ?: throw Unreadable()
        inflater.setInput(input)
        while (true) {
            val count = inflater.inflate(inflated)
            if (count == 0) {
                if (inflater.needsDictionary()) throw Unreadable()
                break
            }
            held(inflated, count)
        }
    }

    /** A frame is all in: a control frame is a message of its own, a last fragment ends one. */
    private fun end() {
        headerRead = 0
        headerNeeded = 2
        if (opcode >= CLOSE) {
            val data = control.toByteArray()
            deliver(controlKind(), data, data.size.toLong())
            return
        }
        if (!fin) return
        val kind = message ?: throw Unreadable()
        val inflater = inflater
        if (compressed && inflater != null) {
            // RFC 7692 leaves the end of a deflate block off every message; it goes back on here.
            inflate(TAIL)
            if (resets || inflater.finished()) inflater.reset()
        }
        deliver(kind, kept.toByteArray(), length)
        message = null
    }

    private fun controlKind(): SocketMessage.Kind =
        when (opcode) {
            CLOSE -> SocketMessage.Kind.Close
            PING -> SocketMessage.Kind.Ping
            else -> SocketMessage.Kind.Pong
        }

    private fun deliver(kind: SocketMessage.Kind, data: ByteArray, whole: Long) {
        onMessage(SocketMessage(fromApp, clock(), kind, data, whole, truncated = whole > data.size))
    }

    private class Unreadable : Exception()

    companion object {
        /** As much of one message as is held on to. */
        const val MAX_KEPT = 64 * 1024

        private const val MAX_HEADER = 14
        private const val MASK = 4
        private const val MAX_CONTROL = 125
        private const val INFLATE_CHUNK = 8 * 1024
        private const val FIN_BIT = 0x80
        private const val RSV1_BIT = 0x40
        private const val RSV2_BIT = 0x20
        private const val RSV3_BIT = 0x10
        private const val OPCODE_BITS = 0x0f
        private const val MASK_BIT = 0x80
        private const val LENGTH_BITS = 0x7f
        private const val LENGTH_16 = 126
        private const val LENGTH_64 = 127
        private const val CONTINUATION = 0
        private const val TEXT = 1
        private const val BINARY = 2
        private const val CLOSE = 8
        private const val PING = 9
        private const val PONG = 10
        private val TAIL = byteArrayOf(0x00, 0x00, 0xff.toByte(), 0xff.toByte())
    }
}
