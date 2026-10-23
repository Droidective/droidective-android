package com.droidective.core.net.proxy

import com.droidective.core.net.Loopback
import com.droidective.core.net.h2.FrameCutter
import com.droidective.core.net.h2.H2Wire
import com.droidective.core.net.h2.H2Wire.END_HEADERS
import com.droidective.core.net.h2.H2Wire.END_STREAM
import com.droidective.core.net.h2.H2Wire.HEADERS
import com.droidective.core.net.h2.H2Wire.SETTINGS
import com.droidective.core.net.h2.H2Wire.data
import com.droidective.core.net.h2.H2Wire.frame
import com.droidective.core.net.h2.H2Wire.headers
import com.droidective.core.net.h2.H2Wire.indexed
import com.droidective.core.net.h2.H2Wire.opening
import com.droidective.core.net.h2.Http2Frame
import com.droidective.core.net.http.BodyFraming
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.HttpBody
import com.droidective.core.net.http.HttpReader
import com.droidective.core.net.proxy.ProxyTls.H2
import com.droidective.core.net.proxy.ProxyTls.HTTP_1_1
import com.droidective.core.net.proxy.TestAuthority.trust
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An app that will speak only HTTP/2 — gRPC, for one — reaching its server through the proxy:
 * carried across, and read from the side into a flow a stream.
 */
class Http2RelayTest {
    private val finished = LinkedBlockingQueue<Flow>()
    private var proxy: ProxyServer? = null

    @AfterTest
    fun stopTheProxy() {
        proxy?.stop()
    }

    private fun proxy(): Int {
        val sink =
            object : FlowSink {
                override fun finished(flow: Flow) {
                    finished += flow
                }
            }
        val running = ProxyServer(TestAuthority.certificates, sink, TestAuthority.upstream)
        proxy = running
        return running.start()
    }

    private fun next(): Flow = finished.poll(10, TimeUnit.SECONDS) ?: error("nothing was recorded")

    @Test
    fun anAppThatSpeaksOnlyHttp2IsReadStreamByStream() {
        H2Origin(H2).use { origin ->
            val port = proxy()
            through(port, origin.port, H2).use { app ->
                assertEquals(H2, app.applicationProtocol)
                app.outputStream.write(
                    opening() +
                        headers(1, GET, ":path" to "/thing", AUTHORITY, "accept" to "*/*") +
                        headers(3, POST, ":path" to "/form", AUTHORITY, end = false) +
                        data(3, "a=1")
                )
                app.outputStream.flush()
                // Both answers got back to the app: it was carried, whatever was read of it.
                assertEquals(setOf(1, 3), answersTo(app, 2))
            }
            val flows = listOf(next(), next()).associateBy { it.request.path }
            val get = assertNotNull(flows["/thing"])
            assertTrue(get.http2)
            assertEquals("GET", get.request.method)
            assertEquals("https://localhost:${origin.port}/thing", get.url)
            assertEquals(listOf(Header("accept", "*/*")), get.request.headers.all)
            assertEquals(200, get.status)
            assertEquals("text/plain", get.response?.headers?.contentType)
            assertEquals("hello", String(assertNotNull(get.responseBody).bytes))
            assertNull(get.failure)
            val post = assertNotNull(flows["/form"])
            assertContentEquals("a=1".toByteArray(), post.requestBody.bytes)
        }
    }

    @Test
    fun whatComesAfterTheBodyIsKept() {
        H2Origin(H2).use { origin ->
            origin.answer = { stream ->
                headers(stream, OK, "content-type" to "application/grpc", end = false) +
                    data(stream, "\u0000\u0000\u0000\u0000\u0000", end = false) +
                    headers(stream, "grpc-status" to "0")
            }
            val port = proxy()
            through(port, origin.port, H2).use { app ->
                app.outputStream.write(
                    opening() + headers(1, POST, ":path" to "/Svc/Call", AUTHORITY)
                )
                app.outputStream.flush()
                answersTo(app, 1)
            }
            assertEquals("0", next().trailers["grpc-status"])
        }
    }

    @Test
    fun nonsenseIsCarriedOnUnread() {
        H2Origin(H2).use { origin ->
            val port = proxy()
            through(port, origin.port, H2).use { app ->
                // Field 0 is no field: the reading stops, and the server, which reads frames and
                // not fields, answers anyway.
                val nonsense = frame(HEADERS, END_HEADERS or END_STREAM, 1, indexed(0))
                app.outputStream.write(opening() + nonsense)
                app.outputStream.flush()
                assertEquals(setOf(1), answersTo(app, 1))
            }
            val row = next()
            assertEquals(Passing.Unreadable, row.passing)
            assertTrue(row.opening)
            assertEquals(
                "the rest passed on unread: it stopped making sense as HTTP/2",
                FlowLabels.passed(row),
            )
        }
    }

    @Test
    fun anAppThatCanSpeakEitherIsReadOverHttp11() {
        // The app's own order puts HTTP/2 first; the server's order is the one that counts.
        FakeServer(TestAuthority.serverContext()).use { origin ->
            origin.reply = { "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello" }
            val port = proxy()
            through(port, origin.port, H2, HTTP_1_1).use { app ->
                assertEquals(HTTP_1_1, app.applicationProtocol)
                app.outputStream.write("GET /thing HTTP/1.1\r\nHost: localhost\r\n\r\n".wire())
                app.outputStream.flush()
                val head = assertNotNull(HttpReader(app.inputStream).readResponse())
                val body = HttpBody.relay(BodyFraming.of(head, "GET"), app.inputStream, null)
                assertEquals("hello", String(body.bytes))
            }
            val flow = next()
            assertNull(flow.passing)
            assertEquals(200, flow.status)
            assertEquals("HTTP/1.1", flow.request.version)
        }
    }

    @Test
    fun aServerThatWontSpeakHttp2IsNamedAsTheReason() {
        // It ignores ALPN altogether, so the handshake succeeds and agrees on nothing.
        H2Origin().use { origin ->
            val port = proxy()
            through(port, origin.port, H2).use { app ->
                assertEquals(H2, app.applicationProtocol)
                // Hung up on: there is nobody at the other end it can talk to.
                assertEquals(-1, app.inputStream.read())
            }
            assertEquals("the app speaks only HTTP/2, and the server would not", next().failure)
        }
    }

    @Test
    fun aServerThatRefusesHttp2SaysSoInWords() {
        H2Origin(HTTP_1_1).use { origin ->
            val port = proxy()
            through(port, origin.port, H2).use { app -> assertEquals(-1, app.inputStream.read()) }
            val failure = assertNotNull(next().failure)
            assertTrue("no application protocol" in failure, failure)
        }
    }

    /** CONNECT to the proxy, then TLS inside it offering [alpn], as the app would. */
    private fun through(proxyPort: Int, originPort: Int, vararg alpn: String): SSLSocket {
        val socket = Socket("127.0.0.1", proxyPort)
        val target = "localhost:$originPort"
        socket.getOutputStream().write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".wire())
        socket.getOutputStream().flush()
        val established = assertNotNull(HttpReader(socket.getInputStream()).readResponse())
        assertEquals(200, established.status)
        val tls =
            trust.socketFactory.createSocket(socket, "localhost", originPort, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply { applicationProtocols = alpn }
        tls.soTimeout = 10_000
        tls.startHandshake()
        return tls
    }

    /** The streams [count] answers came back on, read off [app] as frames. */
    private fun answersTo(app: SSLSocket, count: Int): Set<Int> {
        val ended = LinkedHashSet<Int>()
        val frames = FrameCutter(preface = false) { if (it.ends()) ended += it.stream }
        val buffer = ByteArray(4096)
        while (ended.size < count) {
            val read = app.inputStream.read(buffer)
            if (read == -1) break
            frames.feed(buffer, 0, read)
        }
        return ended
    }

    private companion object {
        val GET = ":method" to "GET"
        val POST = ":method" to "POST"
        val AUTHORITY = ":authority" to "localhost"
        val OK = ":status" to "200"

        fun String.wire() = toByteArray(Charsets.ISO_8859_1)
    }
}

private fun Http2Frame.ends(): Boolean = (type == H2Wire.DATA || type == HEADERS) && has(END_STREAM)

/**
 * A TLS server of the test's own speaking [alpn] — or, given none, paying ALPN no attention. When
 * it speaks HTTP/2 it reads frames, not fields, and answers each stream the app ends with [answer]:
 * so it answers even what the proxy can't make sense of.
 */
private class H2Origin(private vararg val alpn: String) : AutoCloseable {
    private val server =
        TestAuthority.serverContext()
            .serverSocketFactory
            .createServerSocket(0, Loopback.BACKLOG, Loopback.localhost) as SSLServerSocket

    val port: Int
        get() = server.localPort

    @Volatile
    var answer: (Int) -> ByteArray = { stream ->
        headers(stream, ":status" to "200", "content-type" to "text/plain", end = false) +
            data(stream, "hello")
    }

    init {
        thread(isDaemon = true, name = "h2-origin") {
            while (true) {
                val socket =
                    try {
                        server.accept() as SSLSocket
                    } catch (_: IOException) {
                        return@thread
                    }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: SSLSocket) {
        try {
            socket.use {
                if (alpn.isNotEmpty()) {
                    it.sslParameters = it.sslParameters.apply { applicationProtocols = alpn }
                }
                it.startHandshake()
                val out = it.outputStream
                out.write(frame(SETTINGS, 0, 0))
                out.flush()
                val frames =
                    FrameCutter(preface = true) { frame ->
                        if (frame.ends()) {
                            out.write(answer(frame.stream))
                            out.flush()
                        }
                    }
                val buffer = ByteArray(4096)
                while (true) {
                    val read = it.inputStream.read(buffer)
                    if (read == -1) break
                    frames.feed(buffer, 0, read)
                }
            }
        } catch (_: IOException) {
            // The proxy hanging up is how every connection here ends.
        }
    }

    override fun close() = server.close()
}
