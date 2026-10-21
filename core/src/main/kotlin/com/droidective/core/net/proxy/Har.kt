package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Flows written as a HAR 1.2 file, the format every browser's network panel, Charles, Proxyman and
 * HTTP Toolkit read and write: how what the phone saw gets to a computer, a colleague, a bug.
 *
 * Bodies are written decoded, the way HAR means `content.text` — a gzipped answer as the JSON it
 * was, not as bytes — and in base64 when they aren't text. Entries are written one at a time, so a
 * store holding tens of megabytes of bodies is never one string in memory as well.
 *
 * Only exchanges are written. A connection passed through, or refused before anything was asked,
 * has no request in it to repeat; HAR has no shape for it and readers trip on one forced in.
 */
object Har {
    const val VERSION = "1.2"

    /**
     * Writes [flows] to [out] as one HAR log. [creator] is the app's own version, for the header.
     */
    fun write(flows: List<Flow>, creator: String, out: Appendable) {
        out.append("{\"log\":{\"version\":\"$VERSION\",")
        out.append("\"creator\":").append(json(creatorOf(creator))).append(",")
        out.append("\"pages\":[],\"entries\":[")
        var first = true
        for (flow in flows) {
            if (flow.opening) continue
            if (!first) out.append(",")
            out.append(json(entry(flow)))
            first = false
        }
        out.append("]}}")
    }

    /** One exchange as a HAR entry. */
    fun entry(flow: Flow): JsonObject = buildJsonObject {
        put("startedDateTime", Instant.ofEpochMilli(flow.startedAt).toString())
        put("time", flow.durationMillis ?: 0)
        put("request", request(flow))
        put("response", response(flow))
        put("cache", JsonObject(emptyMap()))
        put("timings", timings(flow))
        // HAR lets a writer add its own fields, prefixed with an underscore.
        flow.app?.let { put("_app", it) }
        flow.failure?.let { put("_error", it) }
    }

    private fun creatorOf(version: String) = buildJsonObject {
        put("name", "Droidective")
        put("version", version)
    }

    private fun request(flow: Flow) = buildJsonObject {
        put("method", flow.request.method)
        put("url", flow.url)
        put("httpVersion", flow.request.version)
        put("cookies", JsonArray(emptyList()))
        put("headers", headers(flow.request.headers))
        put("queryString", query(flow.request.path))
        postData(flow)?.let { put("postData", it) }
        put("headersSize", -1)
        put("bodySize", flow.requestBody.bytes.size)
    }

    private fun postData(flow: Flow): JsonObject? {
        val body = flow.requestBody
        if (body.bytes.isEmpty()) return null
        val bytes = BodyView.decode(flow.request.headers.contentEncoding, body.bytes) ?: body.bytes
        return buildJsonObject {
            put("mimeType", flow.request.headers.contentType.orEmpty())
            // postData has no encoding field: a body that isn't text is written as base64 and
            // says so, rather than as replacement characters nobody can turn back into bytes.
            val text = utf8(bytes)
            put("text", text ?: Base64.getEncoder().encodeToString(bytes))
            if (text == null) put("comment", "base64: the body was not text")
            truncation(body)?.let { put("_truncated", it) }
        }
    }

    private fun response(flow: Flow) = buildJsonObject {
        val response = flow.response
        put("status", response?.status ?: 0)
        put("statusText", response?.reason.orEmpty())
        put("httpVersion", response?.version ?: flow.request.version)
        put("cookies", JsonArray(emptyList()))
        put("headers", headers(response?.headers ?: Headers()))
        put("content", content(response?.headers ?: Headers(), flow.responseBody))
        put("redirectURL", response?.headers?.get("Location").orEmpty())
        put("headersSize", -1)
        put("bodySize", flow.responseBody?.bytes?.size ?: -1)
    }

    private fun content(headers: Headers, body: CapturedBody?) = buildJsonObject {
        val kept = body?.bytes ?: ByteArray(0)
        val decoded = BodyView.decode(headers.contentEncoding, kept)
        val bytes = decoded ?: kept
        put("size", bytes.size)
        put("mimeType", headers.contentType.orEmpty())
        if (bytes.isNotEmpty()) {
            val text = utf8(bytes)
            if (text != null) {
                put("text", text)
            } else {
                put("text", Base64.getEncoder().encodeToString(bytes))
                put("encoding", "base64")
            }
        }
        val notes =
            listOfNotNull(
                body?.let(::truncation),
                // br and zstd can't be undone here, so the bytes are as they came off the wire.
                headers.contentEncoding
                    ?.takeIf { decoded == null && kept.isNotEmpty() }
                    ?.let { "still $it-compressed, as it arrived" },
            )
        if (notes.isNotEmpty()) put("comment", notes.joinToString("; "))
    }

    /** HAR wants send, wait and receive; only the whole is known, so it is all waiting. */
    private fun timings(flow: Flow) = buildJsonObject {
        put("blocked", -1)
        put("dns", -1)
        put("connect", -1)
        put("ssl", -1)
        put("send", 0)
        put("wait", flow.durationMillis ?: 0)
        put("receive", 0)
    }

    private fun headers(headers: Headers) = buildJsonArray {
        headers.forEach { header ->
            add(
                buildJsonObject {
                    put("name", header.name)
                    put("value", header.value)
                }
            )
        }
    }

    private fun query(path: String) = buildJsonArray {
        val query = path.substringAfter('?', "")
        if (query.isEmpty()) return@buildJsonArray
        query
            .split('&')
            .filter { it.isNotEmpty() }
            .forEach { pair ->
                add(
                    buildJsonObject {
                        put("name", decoded(pair.substringBefore('=')))
                        put("value", decoded(pair.substringAfter('=', "")))
                    }
                )
            }
    }

    private fun truncation(body: CapturedBody): String? =
        if (body.truncated)
            "only the first ${body.bytes.size} of ${body.wireLength} bytes were kept"
        else null

    private fun decoded(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            value
        }

    private fun utf8(bytes: ByteArray): String? =
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun json(element: JsonObject): String =
        Json.encodeToString(JsonObject.serializer(), element)
}
