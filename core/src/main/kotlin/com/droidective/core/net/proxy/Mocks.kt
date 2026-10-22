package com.droidective.core.net.proxy

import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.ResponseHead
import com.droidective.core.store.JsonFileStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One header of a mocked answer. */
@Serializable data class MockHeader(val name: String, val value: String)

/**
 * An answer the proxy gives itself instead of asking the server: Charles's *Map Local*. The way to
 * see what an app does with a response the server doesn't send yet — a new field, an error, an
 * empty list — without touching the server.
 *
 * @property url the URL it answers, without the query; `*` stands for any run of characters.
 * @property method the method it answers, or null for any.
 */
@Serializable
data class Mock(
    val id: String,
    val url: String,
    val method: String? = null,
    val status: Int = OK,
    val headers: List<MockHeader> = emptyList(),
    val body: String = "",
    val enabled: Boolean = true,
) {
    /** Whether this answers a [method] request for [requestUrl]. */
    fun matches(method: String, requestUrl: String): Boolean {
        if (!enabled) return false
        if (this.method != null && !this.method.equals(method, ignoreCase = true)) return false
        return UrlPattern.matches(url, requestUrl)
    }

    /** The answer as it goes on the wire: the head, its length stated, and the body. */
    fun response(): Pair<ResponseHead, ByteArray> {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        var sent = Headers()
        headers
            .filterNot { ANSWERED_HERE.any { name -> name.equals(it.name, ignoreCase = true) } }
            .forEach { sent = sent.add(it.name, it.value) }
        sent = sent.add(Headers.CONTENT_LENGTH, bytes.size.toString())
        return ResponseHead(HTTP_1_1, status, REASONS[status].orEmpty(), sent) to bytes
    }

    companion object {
        const val OK = 200
        private const val HTTP_1_1 = "HTTP/1.1"

        /**
         * The headers that describe how a body was carried rather than what it says. The mock's
         * body is text as written, so its length is worked out and it is neither compressed nor
         * chunked, whatever the answer it was made from said.
         */
        private val ANSWERED_HERE =
            listOf(Headers.CONTENT_LENGTH, Headers.CONTENT_ENCODING, *Headers.HOP_BY_HOP)

        private val REASONS =
            mapOf(
                200 to "OK",
                201 to "Created",
                204 to "No Content",
                301 to "Moved Permanently",
                302 to "Found",
                304 to "Not Modified",
                400 to "Bad Request",
                401 to "Unauthorized",
                403 to "Forbidden",
                404 to "Not Found",
                409 to "Conflict",
                422 to "Unprocessable Content",
                429 to "Too Many Requests",
                500 to "Internal Server Error",
                502 to "Bad Gateway",
                503 to "Service Unavailable",
                504 to "Gateway Timeout",
            )

        /**
         * A mock made from [flow]'s answer, ready to edit: the same status and headers, and the
         * body as the app read it, decoded. Null when there is no whole text answer to start from.
         */
        fun of(flow: Flow, id: String): Mock? {
            val response = flow.response ?: return null
            val body = flow.responseBody
            if (body?.truncated == true) return null
            val bytes =
                BodyView.decode(response.headers.contentEncoding, body?.bytes ?: ByteArray(0))
            val text = bytes?.let(::utf8) ?: return null
            val kept =
                response.headers.filterNot { header ->
                    ANSWERED_HERE.any { it.equals(header.name, ignoreCase = true) }
                }
            return Mock(
                id = id,
                url = flow.url.substringBefore('?'),
                method = flow.request.method.uppercase(),
                status = response.status,
                headers = kept.map { MockHeader(it.name, it.value) },
                body = text,
            )
        }

        /**
         * Why [flow]'s answer can't be made into a mock, in words that say what to do instead, or
         * null when it can.
         */
        fun whyNot(flow: Flow): String? {
            val response = flow.response ?: return "Nothing came back to mock."
            if (flow.responseBody?.truncated == true) {
                return "Only part of the answer was kept, so a mock would leave the rest out."
            }
            val coding = response.headers.contentEncoding
            val bytes = flow.responseBody?.bytes ?: ByteArray(0)
            if (BodyView.decode(coding, bytes) == null) {
                return "The answer came $coding-compressed, which this can't read. Send again " +
                    "asks for gzip, and its answer can be mocked."
            }
            return if (of(flow, "") == null) "The answer isn't text, so it can't be edited."
            else null
        }

        /** The first enabled mock answering [method] [url], in the order they were made. */
        fun find(mocks: List<Mock>, method: String, url: String): Mock? = mocks.firstOrNull {
            it.matches(method, url)
        }

        /** Whether [url] is a pattern a mock can have: http or https, with a host. */
        fun isPattern(url: String): Boolean = UrlPattern.isPattern(url)

        private fun utf8(bytes: ByteArray): String? =
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
    }
}

/** A mock as its fields are being typed, checked by [build] before it is kept. */
data class MockDraft(
    val url: String,
    val method: String,
    val status: String,
    val headers: String,
    val body: String,
    val enabled: Boolean,
) {
    sealed interface Built {
        data class Ready(val mock: Mock) : Built

        data class Invalid(val reason: String) : Built
    }

    /** The mock with [id] these fields describe, or what is wrong with them. */
    fun build(id: String): Built {
        if (!Mock.isPattern(url))
            return Built.Invalid("The URL needs http:// or https:// and a host.")
        val code =
            status.trim().toIntOrNull()?.takeIf { it in STATUSES }
                ?: return Built.Invalid("Status is a number from 100 to 599.")
        val verb = method.trim().uppercase().ifEmpty { null }
        if (verb != null && !TOKEN.matches(verb))
            return Built.Invalid("\"$method\" isn't a method.")
        val parsed = mutableListOf<MockHeader>()
        for ((number, line) in headers.lines().withIndex()) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            val name = if (colon > 0) line.substring(0, colon).trim() else ""
            if (!TOKEN.matches(name)) return Built.Invalid("Line ${number + 1} isn't a header.")
            parsed += MockHeader(name, line.substring(colon + 1).trim())
        }
        return Built.Ready(Mock(id, url.trim(), verb, code, parsed, body, enabled))
    }

    companion object {
        fun of(mock: Mock) =
            MockDraft(
                url = mock.url,
                method = mock.method.orEmpty(),
                status = mock.status.toString(),
                headers = mock.headers.joinToString("\n") { "${it.name}: ${it.value}" },
                body = mock.body,
                enabled = mock.enabled,
            )

        /** RFC 9110's token: what a method or a header's name may be made of. */
        private val TOKEN = Regex("""[!#$%&'*+\-.^_`|~0-9A-Za-z]+""")
        private val STATUSES = 100..599
    }
}

/** The mocks, kept in `traffic-mocks.json`, in the order they were made. */
class MockRepository(root: File) {
    private val store =
        JsonFileStore(File(root, "traffic-mocks.json"), ListSerializer(Mock.serializer())) {
            emptyList()
        }

    fun all(): List<Mock> = store.load()

    fun save(mocks: List<Mock>) = store.save(mocks)
}
