package com.droidective.core.net.proxy

import com.droidective.core.net.grpc.Grpc
import com.droidective.core.net.grpc.ProtoRaw
import com.droidective.core.net.grpc.ProtoSchema
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.brotli.dec.BrotliInputStream

/** How a body is worth putting on screen. */
sealed interface BodyShape {
    /** Laid out, with the server's own key order kept. */
    data class Json(val pretty: String) : BodyShape

    data class Text(val text: String) : BodyShape

    /** A submitted form, pulled apart so the fields can be read down the page. */
    data class Form(val fields: List<Pair<String, String>>) : BodyShape

    data class Image(val mediaType: String, val bytes: ByteArray) : BodyShape {
        override fun equals(other: Any?) =
            other is Image && mediaType == other.mediaType && bytes.contentEquals(other.bytes)

        override fun hashCode() = 31 * mediaType.hashCode() + bytes.contentHashCode()
    }

    /** Nothing readable, and why — which beats showing a screen of replacement characters. */
    data class Binary(val reason: String, val bytes: ByteArray) : BodyShape {
        override fun equals(other: Any?) =
            other is Binary && reason == other.reason && bytes.contentEquals(other.bytes)

        override fun hashCode() = 31 * reason.hashCode() + bytes.contentHashCode()
    }

    /** A gRPC body, message by message, each read without its schema where it can be. */
    data class Grpc(val messages: List<GrpcShown>) : BodyShape {
        /** Every message as it reads, one after another under its number, for copying. */
        val text: String
            get() =
                messages
                    .mapIndexed { index, message ->
                        "# Message ${index + 1}\n" +
                            (message.fields ?: message.text ?: message.problem)
                    }
                    .joinToString("\n")
    }

    data object Empty : BodyShape
}

/**
 * One message of a gRPC body as it is shown: its fields when it reads as protobuf, its text when it
 * is text instead, and why when it is neither.
 */
data class GrpcShown(
    val wireLength: Int,
    val compressed: Boolean,
    val fields: String? = null,
    val text: String? = null,
    val problem: String? = null,
    /** The message type its fields were named from, when the app's schema had it. */
    val type: String? = null,
)

/**
 * Turning what was captured into what is shown.
 *
 * Almost every API answer on a phone arrives compressed, so a proxy that shows the bytes it
 * captured shows nothing anybody can read. Decoding comes first, then deciding what the body is.
 */
object BodyView {
    /**
     * [body] as it is worth showing. A gRPC body is read as [typed] when the app's schema says what
     * it is, and without the schema when it doesn't.
     */
    fun of(headers: Headers, body: CapturedBody?, typed: ProtoSchema.Typed? = null): BodyShape {
        if (body == null || body.bytes.isEmpty()) return BodyShape.Empty
        if (Grpc.carries(headers.contentType)) return grpc(headers, body, typed)
        val media = mediaType(headers)
        val decoded =
            decode(headers.contentEncoding, body.bytes) ?: return unreadable(headers, body)
        if (media.startsWith("image/")) return BodyShape.Image(media, decoded)
        val text = text(decoded, charset(headers)) ?: return BodyShape.Binary(NOT_TEXT, decoded)
        return shapeOfText(media, text, decoded, body.truncated)
    }

    private fun shapeOfText(
        media: String,
        text: String,
        decoded: ByteArray,
        truncated: Boolean,
    ): BodyShape {
        if (media == FORM) return BodyShape.Form(fields(text))
        // A truncated body is not valid JSON however it started, so it is shown as the text it is.
        if (!truncated && looksLikeJson(text))
            prettyJson(text)?.let {
                return BodyShape.Json(it)
            }
        return if (text.isEmpty()) BodyShape.Binary(NOT_TEXT, decoded) else BodyShape.Text(text)
    }

    private fun grpc(headers: Headers, body: CapturedBody, typed: ProtoSchema.Typed?): BodyShape {
        val shown =
            Grpc.messages(body.bytes, headers[GRPC_ENCODING]).map { message ->
                val problem = message.problem
                val bytes = message.bytes
                when {
                    problem != null ->
                        GrpcShown(message.wireLength, message.compressed, problem = problem)
                    bytes.isEmpty() ->
                        GrpcShown(message.wireLength, message.compressed, fields = "")
                    else -> {
                        typed?.render(bytes)?.let {
                            return@map GrpcShown(
                                message.wireLength,
                                message.compressed,
                                fields = it,
                                type = typed.type,
                            )
                        }
                        val fields = ProtoRaw.parse(bytes)?.let(ProtoRaw::render)
                        val text = if (fields == null) text(bytes, StandardCharsets.UTF_8) else null
                        GrpcShown(
                            message.wireLength,
                            message.compressed,
                            fields = fields,
                            text = text,
                            problem = if (fields == null && text == null) NOT_PROTOBUF else null,
                        )
                    }
                }
            }
        return BodyShape.Grpc(shown)
    }

    private fun unreadable(headers: Headers, body: CapturedBody): BodyShape {
        val coding = headers.contentEncoding ?: return BodyShape.Binary(NOT_TEXT, body.bytes)
        val why =
            if (body.truncated) "only part of it was kept, so the $coding stream cannot be read"
            else "$coding is not a compression this can read"
        return BodyShape.Binary(why, body.bytes)
    }

    /**
     * The body as it was before the wire compressed it, or null when it cannot be undone.
     *
     * A truncated body gives back as much as decoded before the stream ran out, which is usually
     * all of a JSON answer worth reading, rather than nothing at all. Decoding stops at
     * [MAX_DECODED]: a few kept megabytes can unpack to gigabytes, by accident or on purpose.
     */
    fun decode(coding: String?, bytes: ByteArray): ByteArray? =
        when (coding) {
            null -> bytes
            "gzip",
            "x-gzip" -> drain { GZIPInputStream(ByteArrayInputStream(bytes)) }
            "deflate" -> drain { InflaterInputStream(ByteArrayInputStream(bytes), Inflater()) }
            "br" -> drain { BrotliInputStream(ByteArrayInputStream(bytes)) }
            // zstd needs a decoder this app doesn't carry, so the bytes stay as they are.
            else -> null
        }

    /** As much of one body as is decoded, past which the rest is left undone. */
    const val MAX_DECODED = 32 * 1024 * 1024

    private fun drain(open: () -> InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        return try {
            open().use { stream ->
                val buffer = ByteArray(BUFFER)
                while (out.size() < MAX_DECODED) {
                    val read = stream.read(buffer, 0, minOf(BUFFER, MAX_DECODED - out.size()))
                    if (read == -1) break
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray()
        } catch (_: IOException) {
            // A stream that stops part-way still gave up everything before that point.
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }

    /**
     * The text, or null when the bytes are not that charset — which is how binary announces itself.
     */
    private fun text(bytes: ByteArray, charset: Charset): String? {
        val decoder = charset.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun mediaType(headers: Headers): String =
        headers.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    private fun charset(headers: Headers): Charset {
        val named =
            headers.contentType
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"', ' ') ?: return StandardCharsets.UTF_8
        return try {
            Charset.forName(named)
        } catch (_: IllegalCharsetNameException) {
            StandardCharsets.UTF_8
        } catch (_: UnsupportedCharsetException) {
            StandardCharsets.UTF_8
        }
    }

    /**
     * Cheap enough to run before parsing, and wrong only about text that was going to fail anyway.
     */
    private fun looksLikeJson(text: String): Boolean {
        val first = text.firstOrNull { !it.isWhitespace() } ?: return false
        return first == '{' || first == '['
    }

    /**
     * Laid out with the keys in the order the server sent them. Reordering them would hide the
     * thing somebody is most often looking for: what actually changed between two answers.
     */
    fun prettyJson(text: String): String? =
        try {
            val element: JsonElement = Json.parseToJsonElement(text)
            PRETTY.encodeToString(JsonElement.serializer(), element)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun fields(text: String): List<Pair<String, String>> =
        text
            .split('&')
            .filter { it.isNotEmpty() }
            .map { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                form(name) to form(value)
            }

    private fun form(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            value
        }

    private val PRETTY = Json { prettyPrint = true }

    private const val FORM = "application/x-www-form-urlencoded"
    private const val NOT_TEXT = "not text"
    private const val NOT_PROTOBUF = "neither protobuf nor text"
    private const val GRPC_ENCODING = "grpc-encoding"
    private const val BUFFER = 16 * 1024
}
