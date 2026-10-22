package com.droidective.core.net.grpc

import com.droidective.core.net.http.Headers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * How a gRPC call went, from its `grpc-status` and `grpc-message`. The HTTP status of a call is 200
 * whether it worked or not; this is the part that says.
 */
data class GrpcStatus(val code: Int, val message: String?) {
    val ok: Boolean
        get() = code == OK

    /** `NOT_FOUND`, `UNAVAILABLE`…, or the number for a code past the ones gRPC names. */
    val name: String
        get() = NAMES.getOrElse(code) { code.toString() }

    /** Whether the caller can do something about it — the request, its rights — not the server. */
    val callers: Boolean
        get() = code in CALLERS

    /** `INVALID_ARGUMENT: what was wrong`, or just the name. */
    override fun toString(): String = if (message.isNullOrEmpty()) name else "$name: $message"

    companion object {
        const val OK = 0

        /** The status in [trailers], or in [headers] for an answer that was only trailers. */
        fun of(headers: Headers, trailers: Headers): GrpcStatus? {
            val from = if (trailers.has(STATUS)) trailers else headers
            val code = from[STATUS]?.trim()?.toIntOrNull() ?: return null
            return GrpcStatus(code, from[MESSAGE]?.let(::unescaped))
        }

        /**
         * gRPC sends its message percent-encoded as UTF-8, so any text survives being a header. A
         * `%` not followed by two hex digits is taken as itself, as the spec asks.
         */
        private fun unescaped(message: String): String {
            val out = ByteArrayOutputStream()
            var at = 0
            while (at < message.length) {
                val octet = if (message[at] == '%') octetAt(message, at + 1) else null
                if (octet != null) {
                    out.write(octet)
                    at += ESCAPE
                } else {
                    // Header text is Latin-1 here, one octet a character.
                    out.write(message[at].code)
                    at++
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        private fun octetAt(text: String, from: Int): Int? {
            if (from + 2 > text.length) return null
            val high = Character.digit(text[from], HEX)
            val low = Character.digit(text[from + 1], HEX)
            return if (high < 0 || low < 0) null else high * HEX + low
        }

        private const val ESCAPE = 3
        private const val HEX = 16
        private const val STATUS = "grpc-status"
        private const val MESSAGE = "grpc-message"

        private val NAMES =
            listOf(
                "OK",
                "CANCELLED",
                "UNKNOWN",
                "INVALID_ARGUMENT",
                "DEADLINE_EXCEEDED",
                "NOT_FOUND",
                "ALREADY_EXISTS",
                "PERMISSION_DENIED",
                "RESOURCE_EXHAUSTED",
                "FAILED_PRECONDITION",
                "ABORTED",
                "OUT_OF_RANGE",
                "UNIMPLEMENTED",
                "INTERNAL",
                "UNAVAILABLE",
                "DATA_LOSS",
                "UNAUTHENTICATED",
            )

        /** The codes gRPC's own guidance puts on the caller rather than the server. */
        private val CALLERS = setOf(1, 3, 5, 6, 7, 9, 11, 16)
    }
}

/** One message of a gRPC body, opened when it was compressed. */
class GrpcMessage(
    /** The message as the program sent it. */
    val bytes: ByteArray,
    /** How long it was on the wire. */
    val wireLength: Int,
    val compressed: Boolean,
    /** Why it can't be shown whole: cut off by what was kept, or a compression this can't undo. */
    val problem: String? = null,
)

/** gRPC on HTTP/2: what its bodies hold, and what it calls a method. */
object Grpc {
    /** Whether [contentType] says gRPC: `application/grpc`, with `+proto` or another codec. */
    fun carries(contentType: String?): Boolean {
        val media = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return media == GRPC || media.startsWith("$GRPC+")
    }

    /**
     * A binary header — gRPC names them `-bin`, and base64s their value — read as protobuf, which
     * is what they nearly always carry: `grpc-status-details-bin` is a `google.rpc.Status`. Null
     * for any other header, or a value that isn't base64 of a message.
     */
    fun binaryHeader(name: String, value: String, schema: ProtoSchema? = null): String? {
        if (!name.endsWith(BINARY, ignoreCase = true)) return null
        val bytes =
            try {
                Base64.getDecoder().decode(value.trim())
            } catch (_: IllegalArgumentException) {
                return null
            }
        // The one binary header whose type gRPC fixes, read with field names when it can be.
        if (name.equals(STATUS_DETAILS, ignoreCase = true)) {
            schema
                ?.render(bytes, RPC_STATUS)
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    return it
                }
        }
        return ProtoRaw.parse(bytes)?.takeIf { it.isNotEmpty() }?.let(ProtoRaw::render)
    }

    /** `Firestore/GetDocument` from `/google.firestore.v1.Firestore/GetDocument`. */
    fun method(path: String): String? {
        val parts = path.substringBefore('?').trim('/').split('/')
        if (parts.size != 2 || parts.any { it.isEmpty() }) return null
        return parts[0].substringAfterLast('.') + "/" + parts[1]
    }

    /**
     * The messages in [body], each a flag, a four-byte length, and that many bytes (gRPC's
     * "Length-Prefixed-Message"). A compressed one is opened with [encoding], the call's
     * `grpc-encoding`. A body that was cut off ends with however much of its last message came.
     */
    fun messages(body: ByteArray, encoding: String?): List<GrpcMessage> {
        val messages = ArrayList<GrpcMessage>()
        var at = 0
        while (at < body.size) {
            if (body.size - at < PREFIX) {
                messages += GrpcMessage(ByteArray(0), body.size - at, false, CUT_OFF)
                break
            }
            val compressed = body[at].toInt() and COMPRESSED != 0
            val length = lengthAt(body, at + 1)
            val start = at + PREFIX
            val end = minOf(body.size.toLong(), start + length).toInt()
            val wire = body.copyOfRange(start, end)
            messages += message(wire, compressed, encoding, whole = end - start.toLong() == length)
            at = end
        }
        return messages
    }

    private fun message(
        wire: ByteArray,
        compressed: Boolean,
        encoding: String?,
        whole: Boolean,
    ): GrpcMessage {
        if (!whole) return GrpcMessage(wire, wire.size, compressed, CUT_OFF)
        if (!compressed) return GrpcMessage(wire, wire.size, compressed = false)
        val coding = encoding?.trim()?.lowercase()
        val opened =
            when (coding) {
                "gzip" -> inflate { GZIPInputStream(ByteArrayInputStream(wire)) }
                "deflate" -> inflate { InflaterInputStream(ByteArrayInputStream(wire)) }
                else -> null
            }
        return opened?.let { GrpcMessage(it, wire.size, compressed = true) }
            ?: GrpcMessage(wire, wire.size, compressed = true, "compressed with ${coding ?: "?"}")
    }

    private fun inflate(open: () -> InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        return try {
            open().use { stream ->
                val buffer = ByteArray(BUFFER)
                while (out.size() < MAX_OPENED) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray()
        } catch (_: IOException) {
            null
        }
    }

    private fun lengthAt(body: ByteArray, from: Int): Long {
        var length = 0L
        for (index in from until from + LENGTH_BYTES) {
            length = (length shl 8) or (body[index].toLong() and 0xff)
        }
        return length
    }

    private const val GRPC = "application/grpc"
    private const val BINARY = "-bin"
    private const val STATUS_DETAILS = "grpc-status-details-bin"
    private const val RPC_STATUS = "google.rpc.Status"
    private const val PREFIX = 5
    private const val LENGTH_BYTES = 4
    private const val COMPRESSED = 0x01
    private const val BUFFER = 8 * 1024
    private const val CUT_OFF = "only part of it was kept"

    /** As much of one message as is opened: a small one can unpack to gigabytes. */
    private const val MAX_OPENED = 8 * 1024 * 1024
}
