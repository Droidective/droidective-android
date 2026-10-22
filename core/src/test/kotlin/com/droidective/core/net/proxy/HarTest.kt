package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import com.droidective.core.net.ws.SocketMessage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HarTest {
    private fun har(vararg flows: Flow): JsonObject {
        val out = StringBuilder()
        Har.write(flows.toList(), "0.7.0", out)
        return Json.parseToJsonElement(out.toString()).jsonObject.getValue("log").jsonObject
    }

    private fun JsonObject.obj(name: String) = getValue(name).jsonObject

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.int

    private fun entries(log: JsonObject) = log.getValue("entries").jsonArray.map { it.jsonObject }

    @Test
    fun theLogSaysWhatWroteIt() {
        val log = har()
        assertEquals("1.2", log.text("version"))
        assertEquals("Droidective", log.obj("creator").text("name"))
        assertEquals("0.7.0", log.obj("creator").text("version"))
        assertEquals(emptyList(), entries(log))
    }

    @Test
    fun anExchangeIsAnEntry() {
        val json = """{"items":[1,2]}"""
        val flow =
            exchange(
                    path = "/menu?id=3&name=fish%20cake",
                    responseHeaders = Headers().add("Content-Type", "application/json"),
                    responseBody = json.toByteArray(),
                )
                .copy(app = "com.example.shop")
        val entry = entries(har(flow)).single()
        assertEquals("2026-10-22T03:05:06.427Z", entry.text("startedDateTime"))
        assertEquals(120, entry.number("time"))
        assertEquals("com.example.shop", entry.text("_app"))

        val request = entry.obj("request")
        assertEquals("GET", request.text("method"))
        assertEquals("https://api.example.com/menu?id=3&name=fish%20cake", request.text("url"))
        assertEquals("HTTP/1.1", request.text("httpVersion"))
        val query = request.getValue("queryString").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("id" to "3", "name" to "fish cake"),
            query.map { it.text("name") to it.text("value") },
        )
        assertFalse("postData" in request)

        val response = entry.obj("response")
        assertEquals(200, response.number("status"))
        assertEquals("OK", response.text("statusText"))
        val content = response.obj("content")
        assertEquals(json, content.text("text"))
        assertEquals("application/json", content.text("mimeType"))
        assertNull(content["encoding"])
        assertEquals(120, entry.obj("timings").number("wait"))
    }

    @Test
    fun aCompressedAnswerIsWrittenAsWhatItSaid() {
        val zipped = ByteArrayOutputStream()
        GZIPOutputStream(zipped).use { it.write("""{"ok":true}""".toByteArray()) }
        val flow =
            exchange(
                responseHeaders = Headers().add("Content-Encoding", "gzip"),
                responseBody = zipped.toByteArray(),
            )
        val content = entries(har(flow)).single().obj("response").obj("content")
        assertEquals("""{"ok":true}""", content.text("text"))
        assertEquals(11, content.number("size"))
    }

    @Test
    fun aBodyThatIsNotTextIsBase64() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 0xFF.toByte())
        val flow =
            exchange(
                responseHeaders = Headers().add("Content-Type", "image/png"),
                responseBody = png,
            )
        val content = entries(har(flow)).single().obj("response").obj("content")
        assertEquals("base64", content.text("encoding"))
        assertTrue(Base64.getDecoder().decode(content.text("text")).contentEquals(png))
    }

    @Test
    fun aBodyThatCannotBeDecodedSaysItIsStillCompressed() {
        val zstd = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())
        val flow =
            exchange(
                responseHeaders = Headers().add("Content-Encoding", "zstd"),
                responseBody = zstd,
            )
        val content = entries(har(flow)).single().obj("response").obj("content")
        assertEquals("still zstd-compressed, as it arrived", content.text("comment"))
        assertTrue(Base64.getDecoder().decode(content.text("text")).contentEquals(zstd))
    }

    @Test
    fun aSentBodyIsItsPostData() {
        val form = "name=fish+cake&qty=2"
        val flow =
            exchange(
                method = "POST",
                requestHeaders = Headers().add("Content-Type", "application/x-www-form-urlencoded"),
                requestBody = form.toByteArray(),
            )
        val post = entries(har(flow)).single().obj("request").obj("postData")
        assertEquals("application/x-www-form-urlencoded", post.text("mimeType"))
        assertEquals(form, post.text("text"))
    }

    @Test
    fun aClippedBodySaysSo() {
        val flow =
            exchange(responseBody = "abc".toByteArray())
                .copy(responseBody = CapturedBody("abc".toByteArray(), 9_000, truncated = true))
        val content = entries(har(flow)).single().obj("response").obj("content")
        assertEquals("only the first 3 of 9000 bytes were kept", content.text("comment"))
    }

    @Test
    fun anExchangeWithNoAnswerHasStatusZeroAndTheReason() {
        val failed =
            exchange().copy(response = null, responseBody = null, failure = "connect timed out")
        val entry = entries(har(failed)).single()
        assertEquals(0, entry.obj("response").number("status"))
        assertEquals("connect timed out", entry.text("_error"))
    }

    @Test
    fun connectionsNeverReadAreLeftOut() {
        val passed =
            exchange(method = "CONNECT", path = "api.example.com:443")
                .copy(response = null, responseBody = null, tunnelled = true)
        val refused =
            exchange(method = "CONNECT", path = "api.example.com:443")
                .copy(response = null, responseBody = null, failure = "the app refused")
        val read = exchange(path = "/kept")
        val urls = entries(har(passed, read, refused)).map { it.obj("request").text("url") }
        assertEquals(listOf("https://api.example.com/kept"), urls)
    }

    @Test
    fun headersKeepTheirOrderAndRepeats() {
        val headers =
            Headers().add("Set-Cookie", "a=1").add("X-Trace", "7").add("Set-Cookie", "b=2")
        val entry = entries(har(exchange(responseHeaders = headers))).single()
        val written = entry.obj("response").getValue("headers") as JsonArray
        assertEquals(
            listOf("Set-Cookie" to "a=1", "X-Trace" to "7", "Set-Cookie" to "b=2"),
            written.map { it.jsonObject.text("name") to it.jsonObject.text("value") },
        )
    }

    @Test
    fun aSocketsMessagesAreWrittenAsChromeWritesThem() {
        val socket =
            exchange()
                .copy(
                    response =
                        ResponseHead(
                            "HTTP/1.1",
                            101,
                            "Switching Protocols",
                            Headers().add("Upgrade", "websocket"),
                        )
                )
        val said =
            listOf(
                message(
                    fromApp = true,
                    SocketMessage.Kind.Text,
                    "{\"subscribe\":\"prices\"}".toByteArray(),
                ),
                message(fromApp = false, SocketMessage.Kind.Binary, byteArrayOf(1, 2, 3)),
                message(fromApp = false, SocketMessage.Kind.Ping, ByteArray(0)),
            )
        val out = StringBuilder()
        Har.write(listOf(socket, exchange().copy(id = 2)), "0.9.0", out) {
            if (it == 1L) said else emptyList()
        }
        val (first, second) = entries(Json.parseToJsonElement(out.toString()).jsonObject.obj("log"))

        val written = first.getValue("_webSocketMessages").jsonArray.map { it.jsonObject }
        // Text and binary, as Chrome keeps them; a ping is the connection's business.
        assertEquals(2, written.size)
        assertEquals("send", written[0].text("type"))
        assertEquals(1, written[0].number("opcode"))
        assertEquals("{\"subscribe\":\"prices\"}", written[0].text("data"))
        assertEquals(1_792_638_306.5, written[0].getValue("time").jsonPrimitive.content.toDouble())
        assertEquals("receive", written[1].text("type"))
        assertEquals(2, written[1].number("opcode"))
        assertEquals(
            Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
            written[1].text("data"),
        )
        assertFalse("_webSocketMessages" in second)
    }

    @Test
    fun aSocketIsAnUpgradeToAWebSocket() {
        val upgraded = { protocol: String ->
            exchange()
                .copy(
                    response = ResponseHead("HTTP/1.1", 101, "", Headers().add("Upgrade", protocol))
                )
        }
        assertTrue(upgraded("WebSocket").socket)
        assertFalse(upgraded("h2c").socket)
        assertFalse(exchange().socket)
        assertTrue(FlowFilter.parse("is:socket").matches(upgraded("websocket")))
    }

    private fun message(fromApp: Boolean, kind: SocketMessage.Kind, data: ByteArray) =
        SocketMessage(fromApp, 1_792_638_306_500, kind, data, data.size.toLong(), truncated = false)

    private fun exchange(
        method: String = "GET",
        path: String = "/thing",
        requestHeaders: Headers = Headers(),
        requestBody: ByteArray = ByteArray(0),
        responseHeaders: Headers = Headers(),
        responseBody: ByteArray = ByteArray(0),
    ) =
        Flow(
            id = 1,
            startedAt = 1_792_638_306_427,
            secure = true,
            host = "api.example.com",
            port = 443,
            request = RequestHead(method, path, "HTTP/1.1", requestHeaders),
            requestBody = CapturedBody(requestBody, requestBody.size.toLong(), false),
            response = ResponseHead("HTTP/1.1", 200, "OK", responseHeaders),
            responseBody = CapturedBody(responseBody, responseBody.size.toLong(), false),
            finishedAt = 1_792_638_306_547,
        )
}
