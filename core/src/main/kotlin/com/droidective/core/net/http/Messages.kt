package com.droidective.core.net.http

import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets

/** A request's first line: `GET /thing HTTP/1.1`. */
data class RequestLine(val method: String, val target: String, val version: String) {
    companion object {
        /** Null when [line] is not three space-separated parts ending in a version. */
        fun parse(line: String): RequestLine? {
            val parts = line.split(' ')
            if (parts.size != PARTS) return null
            val (method, target, version) = parts
            if (!isToken(method) || target.isEmpty() || !isVersion(version)) return null
            return RequestLine(method, target, version)
        }
    }
}

/** A response's first line: `HTTP/1.1 200 OK`. The reason may be empty, and often is. */
data class StatusLine(val version: String, val status: Int, val reason: String) {
    companion object {
        fun parse(line: String): StatusLine? {
            val parts = line.split(' ', limit = PARTS)
            if (parts.size < 2) return null
            val version = parts[0]
            val status = parts[1].toIntOrNull() ?: return null
            if (!isVersion(version) || status !in MIN_STATUS..MAX_STATUS) return null
            return StatusLine(version, status, parts.getOrElse(2) { "" })
        }
    }
}

/** Everything a request says before its body. */
data class RequestHead(
    val method: String,
    val target: String,
    val version: String,
    val headers: Headers,
) {
    /** Whether the target names a whole URL, which is how a request addressed to a proxy reads. */
    val absolute: Boolean
        get() = "://" in target

    /**
     * The target as an origin server wants it — a path and query. A proxy is handed the whole URL
     * and has to put this back before forwarding, or the server answers 400.
     */
    val path: String
        get() {
            if (!absolute) return target
            val uri =
                try {
                    URI(target)
                } catch (_: URISyntaxException) {
                    return target
                }
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return uri.rawPath.ifEmpty { "/" } + query
        }

    fun bytes(): ByteArray = head("$method $target $version", headers)

    fun with(target: String = this.target, headers: Headers = this.headers): RequestHead =
        copy(target = target, headers = headers)
}

/** Everything a response says before its body. */
data class ResponseHead(
    val version: String,
    val status: Int,
    val reason: String,
    val headers: Headers,
) {
    fun bytes(): ByteArray =
        head(
            listOf(version, status.toString(), reason).filter { it.isNotEmpty() }.joinToString(" "),
            headers,
        )
}

/**
 * How a message says where its body ends. Getting this wrong does not corrupt one message, it
 * desynchronises the connection: the next request is read out of the last one's body.
 */
sealed interface BodyFraming {
    /** No body at all, which is not the same as a body of nothing. */
    data object Empty : BodyFraming

    data class Fixed(val length: Long) : BodyFraming

    data object Chunked : BodyFraming

    /** The body runs until the connection closes, so the connection cannot be reused. */
    data object UntilClose : BodyFraming

    companion object {
        /** A request without a stated length has no body; it never runs until close. */
        fun of(head: RequestHead): BodyFraming =
            when {
                head.headers.chunked -> Chunked
                else -> head.headers.contentLength?.let(::Fixed) ?: Empty
            }

        /**
         * A response's framing, which depends on the request it answers: a HEAD is answered with a
         * length but no body, and so is a 304.
         */
        fun of(head: ResponseHead, requestMethod: String): BodyFraming =
            when {
                requestMethod.equals("HEAD", ignoreCase = true) -> Empty
                head.status in INFORMATIONAL || head.status == NO_CONTENT -> Empty
                head.status == NOT_MODIFIED -> Empty
                requestMethod.equals("CONNECT", ignoreCase = true) && head.status in SUCCESS ->
                    Empty
                head.headers.chunked -> Chunked
                else -> head.headers.contentLength?.let(::Fixed) ?: UntilClose
            }

        private val INFORMATIONAL = 100..199
        private val SUCCESS = 200..299
        private const val NO_CONTENT = 204
        private const val NOT_MODIFIED = 304
    }
}

internal const val HTTP_1_1 = "HTTP/1.1"
internal const val CRLF = "\r\n"

private const val PARTS = 3
private const val MIN_STATUS = 100
private const val MAX_STATUS = 599

private fun head(line: String, headers: Headers): ByteArray {
    val text = buildString {
        append(line).append(CRLF)
        headers.forEach { append(it.name).append(": ").append(it.value).append(CRLF) }
        append(CRLF)
    }
    // Header bytes are Latin-1 by specification; anything else was already encoded by the sender.
    return text.toByteArray(StandardCharsets.ISO_8859_1)
}

private fun isVersion(text: String) = text.startsWith("HTTP/") && text.length > "HTTP/".length

/** A method is a token: visible characters, no separators. */
private fun isToken(text: String) =
    text.isNotEmpty() && text.all { it.code in 0x21..0x7E && it !in TOKEN_SEPARATORS }

private val TOKEN_SEPARATORS = "()<>@,;:\\\"/[]?={}".toSet()
