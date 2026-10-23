package com.droidective.core.net.proxy

import com.droidective.core.net.Loopback
import com.droidective.core.net.h2.H2Wire.frame
import com.droidective.core.net.h2.H2Wire.headers
import com.droidective.core.net.h2.Http2Frame
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.HTTP_2
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeaterTest {
    private fun captured(
        method: String = "GET",
        target: String = "/thing?x=1",
        headers: Headers = Headers(),
        body: ByteArray = ByteArray(0),
        port: Int,
        secure: Boolean = false,
    ) =
        Flow(
            id = 7,
            startedAt = 1_000,
            secure = secure,
            host = "127.0.0.1",
            port = port,
            request = RequestHead(method, target, "HTTP/1.1", headers),
            requestBody = CapturedBody(body, body.size.toLong(), truncated = false),
        )

    private fun ready(flow: Flow) = assertIs<Repeat.Ready>(Repeater.prepare(flow)).outgoing

    @Test
    fun aRequestGoesAgainAndItsAnswerIsRecorded() {
        FakeServer().use { origin ->
            origin.reply = { ok("hello again") }
            val again = Repeater().send(ready(captured(port = origin.port)), id = 42, of = 7)

            assertEquals(42, again.id)
            assertEquals(7, again.repeatOf)
            assertEquals(200, again.status)
            assertContentEquals(
                "hello again".toByteArray(),
                assertNotNull(again.responseBody).bytes,
            )
            assertNotNull(again.finishedAt)
            val asked = origin.received.single()
            assertEquals("GET", asked.method)
            assertEquals("/thing?x=1", asked.target)
        }
    }

    @Test
    fun aRepeatGoesWhereARemapSendsItAndSaysSo() {
        FakeServer().use { local ->
            val via = Server(false, Origin("127.0.0.1", local.port))
            val again = Repeater().send(ready(captured(port = 1)), id = 43, of = 7, via = via)

            assertEquals(200, again.status)
            assertEquals(1, again.port)
            assertEquals("http://127.0.0.1:${local.port}", again.sentTo)
            assertEquals("127.0.0.1:${local.port}", local.received.single().headers["host"])
        }
    }

    @Test
    fun aBodyGoesWholeWithItsLengthStated() {
        FakeServer().use { origin ->
            val body = """{"order":42}""".toByteArray()
            // It came in chunks the first time; it goes out in one piece with a length.
            val headers =
                Headers()
                    .add("Content-Type", "application/json")
                    .add("Transfer-Encoding", "chunked")
                    .add("Proxy-Connection", "keep-alive")
            val flow = captured("POST", "/orders", headers, body, port = origin.port)
            Repeater().send(ready(flow), id = 1, of = 7)

            val asked = origin.received.single()
            assertEquals("""{"order":42}""", asked.body)
            assertEquals("12", asked.headers["content-length"])
            assertEquals("application/json", asked.headers["content-type"])
            assertNull(asked.headers["transfer-encoding"])
            assertNull(asked.headers["proxy-connection"])
        }
    }

    @Test
    fun aRequestAProxyWasHandedAsAWholeUrlGoesToTheServerAsAPath() {
        val flow =
            captured(target = "http://api.example.com/menu?id=3", port = 80)
                .copy(host = "api.example.com")
        val head = ready(flow).head
        assertEquals("/menu?id=3", head.target)
        assertEquals("api.example.com", head.headers["Host"])
        assertEquals("close", head.headers["Connection"])
    }

    @Test
    fun aRepeatAsksOnlyForWhatItCanRead() {
        // Chrome asks for dictionary-compressed answers too, which show nothing here.
        val headers = Headers().add("Accept-Encoding", "gzip, deflate, br, zstd, dcb, dcz")
        val head = ready(captured(headers = headers, port = 80)).head
        assertEquals("gzip, deflate, br, zstd", head.headers["Accept-Encoding"])
        assertEquals("gzip;q=1.0, deflate", Repeater.readable("gzip;q=1.0, deflate, dcz"))
        assertEquals("identity", Repeater.readable("dcz"))
        // What it can read already is left exactly as it was.
        assertNull(Repeater.readable("gzip, deflate, br, zstd"))
        assertNull(Repeater.readable(null))
    }

    @Test
    fun aConnectionNeverReadHasNothingToSend() {
        val passed =
            captured(method = "CONNECT", target = "api.example.com:443", port = 443)
                .copy(tunnelled = true)
        assertIs<Repeat.Refused>(Repeater.prepare(passed))
    }

    @Test
    fun aBodyKeptOnlyInPartIsNotSentAsIfWhole() {
        val clipped =
            captured("POST", port = 80)
                .copy(requestBody = CapturedBody(ByteArray(10), 9_000, truncated = true))
        val refused = assertIs<Repeat.Refused>(Repeater.prepare(clipped))
        assertTrue("part" in refused.reason)
    }

    @Test
    fun aServerThatIsNotThereIsAFailureOnRecord() {
        val closed = Loopback.closedPort()
        val again = Repeater().send(ready(captured(port = closed)), id = 3, of = 7)
        assertNull(again.response)
        assertNotNull(again.failure)
        assertNotNull(again.finishedAt)
    }

    @Test
    fun anHttpsRequestGoesAgainOverTls() {
        FakeServer(TestAuthority.serverContext()).use { origin ->
            origin.reply = { ok("secret") }
            val flow =
                captured(port = origin.port, secure = true, target = "/private")
                    .copy(host = "localhost")
            val again = Repeater(TestAuthority.upstream).send(ready(flow), id = 5, of = 7)
            assertEquals(200, again.status)
            assertContentEquals("secret".toByteArray(), assertNotNull(again.responseBody).bytes)
        }
    }

    @Test
    fun aGrpcCallGoesAgainOnHttp2AndItsTrailersComeBack() {
        H2Origin(ProxyTls.H2).use { origin ->
            origin.answer = { stream ->
                headers(
                    stream,
                    ":status" to "200",
                    "content-type" to "application/grpc",
                    end = false,
                ) +
                    frame(Http2Frame.DATA, 0, stream, framed("hi")) +
                    headers(stream, "grpc-status" to "0")
            }
            val again =
                Repeater(TestAuthority.upstream).send(ready(grpcCall(origin.port)), id = 9, of = 7)

            assertNull(again.failure)
            assertEquals(200, again.status)
            assertTrue(again.http2)
            assertContentEquals(framed("hi"), assertNotNull(again.responseBody).bytes)
            assertEquals("0", again.trailers["grpc-status"])
            val asked = origin.asked.single()
            assertEquals(
                listOf(
                    ":method" to "POST",
                    ":scheme" to "https",
                    ":authority" to "localhost:${origin.port}",
                    ":path" to "/droidective.check.Check/Echo",
                ),
                asked.take(4).map { it.name to it.value },
            )
            assertEquals("trailers", asked.single { it.name == "te" }.value)
            assertEquals("grpc-java-okhttp/1.70.0", asked.single { it.name == "user-agent" }.value)
            assertTrue(asked.none { it.name == "host" }, "Host goes as :authority")
        }
    }

    @Test
    fun aServerThatWontSpeakHttp2IsWhyACallCantGoAgain() {
        // One that pays ALPN no attention agrees to nothing, as a server with HTTP/1.1 alone does.
        H2Origin().use { origin ->
            val again =
                Repeater(TestAuthority.upstream).send(ready(grpcCall(origin.port)), id = 9, of = 7)
            assertNull(again.response)
            assertEquals("the server would not speak HTTP/2, which gRPC needs", again.failure)
        }
    }

    /** A gRPC call an app made, as the proxy read it off HTTP/2. */
    private fun grpcCall(port: Int): Flow {
        val body = framed("hello")
        return Flow(
            id = 7,
            startedAt = 1_000,
            secure = true,
            host = "localhost",
            port = port,
            request =
                RequestHead(
                    "POST",
                    "/droidective.check.Check/Echo",
                    HTTP_2,
                    Headers()
                        .add("content-type", "application/grpc")
                        .add("te", "trailers")
                        .add("user-agent", "grpc-java-okhttp/1.70.0"),
                ),
            requestBody = CapturedBody(body, body.size.toLong(), truncated = false),
        )
    }

    private companion object {
        fun ok(body: String) = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"

        /** One gRPC message, uncompressed, in its five-byte frame. */
        fun framed(message: String): ByteArray {
            val bytes = message.toByteArray()
            return byteArrayOf(0, 0, 0, 0, bytes.size.toByte()) + bytes
        }
    }
}
