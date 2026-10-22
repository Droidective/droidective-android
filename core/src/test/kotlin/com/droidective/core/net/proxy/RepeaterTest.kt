package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.tls.ProxyCertificates
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
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
        // Chrome asks for brotli and zstd, and an answer in either shows nothing here.
        val headers = Headers().add("Accept-Encoding", "gzip, deflate, br, zstd")
        val head = ready(captured(headers = headers, port = 80)).head
        assertEquals("gzip, deflate", head.headers["Accept-Encoding"])
        assertEquals("gzip;q=1.0, deflate", Repeater.readable("gzip;q=1.0, deflate, br"))
        assertEquals("identity", Repeater.readable("br"))
        // What it can read already is left exactly as it was.
        assertNull(Repeater.readable("gzip, deflate"))
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
        val closed = ServerSocket(0).use { it.localPort }
        val again = Repeater().send(ready(captured(port = closed)), id = 3, of = 7)
        assertNull(again.response)
        assertNotNull(again.failure)
        assertNotNull(again.finishedAt)
    }

    @Test
    fun anHttpsRequestGoesAgainOverTls() {
        FakeServer(
                ProxyTls.serverContext(
                    certificates.certificateFor("localhost"),
                    certificates.caCertificate,
                )
            )
            .use { origin ->
                origin.reply = { ok("secret") }
                val flow =
                    captured(port = origin.port, secure = true, target = "/private")
                        .copy(host = "localhost")
                val again = Repeater(trusting).send(ready(flow), id = 5, of = 7)
                assertEquals(200, again.status)
                assertContentEquals("secret".toByteArray(), assertNotNull(again.responseBody).bytes)
            }
    }

    private companion object {
        val certificates: ProxyCertificates by lazy { ProxyCertificates.create("Droidective Test") }

        /** The real server's end, trusting the test authority, as the proxy's tests do. */
        val trusting = UpstreamTls { origin, timeout ->
            val plain = Dial.connect(origin.host, origin.port, timeout)
            (context.socketFactory.createSocket(plain, origin.host, origin.port, true) as SSLSocket)
                .apply { startHandshake() }
        }

        val context: SSLContext by lazy {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null)
            store.setCertificateEntry("ca", certificates.caCertificate)
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        }

        fun ok(body: String) = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"
    }
}
