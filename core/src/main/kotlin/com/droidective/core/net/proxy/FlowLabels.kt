package com.droidective.core.net.proxy

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which way an exchange went, for the colour a row is drawn in. */
enum class FlowOutcome {
    /** Still waiting for the answer. */
    Pending,
    Informational,
    Success,
    Redirect,
    ClientError,
    ServerError,

    /** Nothing came back: the server could not be reached, or the app refused the certificate. */
    Failed,

    /** Left alone by the rules: only that it happened is known. */
    PassedThrough,
}

/** How a flow reads in a list: short, and the same wherever one is shown. */
object FlowLabels {
    fun outcome(flow: Flow): FlowOutcome {
        if (flow.failure != null) return FlowOutcome.Failed
        if (flow.tunnelled && flow.response == null) return FlowOutcome.PassedThrough
        val status = flow.status ?: return FlowOutcome.Pending
        return when (status / CLASS) {
            1 -> FlowOutcome.Informational
            2 -> FlowOutcome.Success
            3 -> FlowOutcome.Redirect
            4 -> FlowOutcome.ClientError
            else -> FlowOutcome.ServerError
        }
    }

    /** The status code, or a word for an exchange that has none. */
    fun status(flow: Flow): String =
        when (outcome(flow)) {
            FlowOutcome.Failed -> "Failed"
            FlowOutcome.PassedThrough -> "Passed"
            FlowOutcome.Pending -> "…"
            else -> flow.status.toString()
        }

    /** Why a connection was passed through rather than read, or null when it was read. */
    fun passed(flow: Flow): String? {
        if (!flow.tunnelled || flow.response != null) return null
        return when (flow.passing) {
            Passing.Rule -> "passed through by a rule"
            Passing.OtherApp -> "passed through: not an app being read"
            Passing.Refused -> "passed through: the app refused the certificate before"
            Passing.Http2 -> "passed on unread: it speaks only HTTP/2"
            null -> "passed through"
        }
    }

    /** What follows the host: the path and query, or nothing for a connection never read. */
    fun path(flow: Flow): String = if (flow.opening) "" else flow.request.path

    /** "0 B", "980 B", "1.2 KB", "14 MB": two significant figures at most, in powers of 1000. */
    fun size(bytes: Long): String {
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= THOUSAND && unit < SIZES.lastIndex) {
            value /= THOUSAND
            unit++
        }
        return "${figures(value, unit == 0)} ${SIZES[unit]}"
    }

    /** "38 ms", "1.2 s", "12 s", "3 min": the scale anybody reading a waterfall thinks in. */
    fun duration(millis: Long): String =
        when {
            millis < SECOND -> "$millis ms"
            millis < MINUTE -> "${figures(millis / SECOND.toDouble(), whole = false)} s"
            else -> "${(millis / MINUTE.toDouble()).roundToLong()} min"
        }

    /**
     * The operation a GraphQL request names, which is what tells one apart from the next: every
     * request an Apollo or Relay app makes is a POST to the same `/graphql`.
     *
     * Read from `operationName` when the client sent one, and otherwise from the query's first
     * line. Null for anything that is not a GraphQL request with a name.
     */
    fun graphQlOperation(flow: Flow): String? {
        val body = flow.requestBody
        if (body.bytes.isEmpty() || body.truncated || body.bytes.size > MAX_GRAPHQL_BODY) {
            return flow.request.queryParameter(OPERATION_NAME)
        }
        val coding = flow.request.headers.contentEncoding
        val bytes = BodyView.decode(coding, body.bytes) ?: return null
        val text = utf8(bytes) ?: return null
        if (text.firstOrNull { !it.isWhitespace() } != '{') return null
        val json =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        named(json[OPERATION_NAME])?.let {
            return it
        }
        val query = named(json[QUERY]) ?: return null
        return OPERATION.find(query)?.groupValues?.get(2)
    }

    private fun named(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun utf8(bytes: ByteArray): String? =
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun figures(value: Double, whole: Boolean): String =
        if (whole || value >= TEN) value.roundToLong().toString()
        else String.format(Locale.ROOT, "%.1f", value)

    private val SIZES = listOf("B", "KB", "MB", "GB")
    private val OPERATION =
        Regex("""^\s*(query|mutation|subscription)\s+([_A-Za-z][_0-9A-Za-z]*)""")

    private const val OPERATION_NAME = "operationName"
    private const val QUERY = "query"
    private const val CLASS = 100
    private const val THOUSAND = 1_000.0
    private const val TEN = 10.0
    private const val SECOND = 1_000L
    private const val MINUTE = 60_000L

    /** A GraphQL request is small; past this it is something else and not worth parsing. */
    private const val MAX_GRAPHQL_BODY = 256 * 1024
}

/**
 * The value of query parameter [name] in the request's target, decoded, or null. Persisted queries
 * send a GraphQL request as a GET, with the operation's name in the URL.
 */
private fun com.droidective.core.net.http.RequestHead.queryParameter(name: String): String? {
    val query =
        path.substringAfter('?', "").ifEmpty {
            return null
        }
    return query
        .split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.let {
            try {
                java.net.URLDecoder.decode(it, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                it
            }
        }
        ?.takeIf { it.isNotBlank() }
}
