package com.droidective.core.net.h2

/** What one direction of an HTTP/2 connection said that stopped making sense as HTTP/2. */
class Http2Exception(message: String) : Exception(message)

/** One HTTP/2 frame (RFC 9113, section 4.1): its type, its flags, its stream, what it carries. */
class Http2Frame(val type: Int, val flags: Int, val stream: Int, val payload: ByteArray) {
    fun has(flag: Int): Boolean = flags and flag != 0

    /** Where a DATA or HEADERS frame's own octets start and end, its padding left out. */
    internal fun unpadded(): Pair<Int, Int> {
        if (!has(PADDED)) return 0 to payload.size
        if (payload.isEmpty()) throw Http2Exception("a padded frame with no padding length")
        val padding = payload[0].toInt() and BYTE
        val to = payload.size - padding
        if (to < 1) throw Http2Exception("more padding than frame")
        return 1 to to
    }

    companion object {
        const val DATA = 0x0
        const val HEADERS = 0x1
        const val PRIORITY_FRAME = 0x2
        const val RST_STREAM = 0x3
        const val SETTINGS = 0x4
        const val PUSH_PROMISE = 0x5
        const val PING = 0x6
        const val GOAWAY = 0x7
        const val WINDOW_UPDATE = 0x8
        const val CONTINUATION = 0x9

        const val END_STREAM = 0x1
        const val ACK = 0x1
        const val END_HEADERS = 0x4
        const val PADDED = 0x8
        const val PRIORITY = 0x20

        /** The octets a HEADERS frame's priority takes, when its flag says it has one. */
        const val PRIORITY_OCTETS = 5

        const val NO_ERROR = 0

        /** RFC 9113's error code [code], in words. */
        fun error(code: Int): String = ERRORS.getOrElse(code) { "error $code" }

        private const val BYTE = 0xff

        private val ERRORS =
            listOf(
                "no error",
                "protocol error",
                "internal error",
                "flow control error",
                "settings timeout",
                "stream closed",
                "frame size error",
                "refused",
                "cancelled",
                "compression error",
                "connect error",
                "enhance your calm",
                "inadequate security",
                "HTTP/1.1 required",
            )
    }
}

/**
 * Cuts one direction of a connection into frames, however its bytes happen to arrive. The app's
 * direction opens with the connection preface, which is checked and passed over.
 */
internal class FrameCutter(preface: Boolean, private val onFrame: (Http2Frame) -> Unit) {
    private var prefaceRead = if (preface) 0 else PREFACE.size
    private val header = ByteArray(HEADER)
    private var headerRead = 0
    private var payload: ByteArray? = null
    private var payloadRead = 0

    /** Takes [count] bytes of [bytes] from [offset], handing on every frame they complete. */
    fun feed(bytes: ByteArray, offset: Int, count: Int) {
        var at = offset
        val end = offset + count
        while (at < end) {
            at =
                when {
                    prefaceRead < PREFACE.size -> preface(bytes, at, end)
                    payload == null -> header(bytes, at, end)
                    else -> payload(bytes, at, end)
                }
        }
    }

    private fun preface(bytes: ByteArray, from: Int, end: Int): Int {
        var at = from
        while (at < end && prefaceRead < PREFACE.size) {
            if (bytes[at] != PREFACE[prefaceRead]) throw Http2Exception("no HTTP/2 preface")
            at++
            prefaceRead++
        }
        return at
    }

    private fun header(bytes: ByteArray, from: Int, end: Int): Int {
        val take = minOf(end - from, HEADER - headerRead)
        System.arraycopy(bytes, from, header, headerRead, take)
        headerRead += take
        if (headerRead == HEADER) {
            payload = ByteArray(octets(0, LENGTH_OCTETS))
            payloadRead = 0
            if (payload?.isEmpty() == true) emit()
        }
        return from + take
    }

    private fun payload(bytes: ByteArray, from: Int, end: Int): Int {
        val into = checkNotNull(payload)
        val take = minOf(end - from, into.size - payloadRead)
        System.arraycopy(bytes, from, into, payloadRead, take)
        payloadRead += take
        if (payloadRead == into.size) emit()
        return from + take
    }

    private fun emit() {
        val type = header[TYPE].toInt() and BYTE
        val flags = header[FLAGS].toInt() and BYTE
        // The top bit is reserved, and a receiver ignores it.
        val stream = octets(STREAM, STREAM_OCTETS) and Int.MAX_VALUE
        val frame = Http2Frame(type, flags, stream, checkNotNull(payload))
        headerRead = 0
        payload = null
        onFrame(frame)
    }

    private fun octets(from: Int, count: Int): Int {
        var value = 0
        for (at in from until from + count) {
            value = (value shl BITS) or (header[at].toInt() and BYTE)
        }
        return value
    }

    companion object {
        /** What every connection opens with, from the app (RFC 9113, section 3.4). */
        val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        private const val HEADER = 9
        private const val LENGTH_OCTETS = 3
        private const val TYPE = 3
        private const val FLAGS = 4
        private const val STREAM = 5
        private const val STREAM_OCTETS = 4
        private const val BYTE = 0xff
        private const val BITS = 8
    }
}
