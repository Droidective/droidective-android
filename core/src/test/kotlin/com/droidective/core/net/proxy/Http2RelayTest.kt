package com.droidective.core.net.proxy

import com.droidective.core.net.http.BodyFraming
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
 * An app that will speak only HTTP/2 — gRPC, for one — reaching its server through the proxy, which
 * reads HTTP/1.1. Offered nothing in common, such an app could not get a word out.
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
                override fun started(flow: Flow) = Unit

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
    fun anAppThatSpeaksOnlyHttp2IsCarriedToTheServer() {
        EchoServer(H2).use { origin ->
            val port = proxy()
            through(port, origin.port, H2).use { app ->
                assertEquals(H2, app.applicationProtocol)
                app.outputStream.write(PREFACE)
                app.outputStream.flush()
                assertContentEquals(PREFACE, app.inputStream.readNBytes(PREFACE.size))
            }
            assertEquals(H2, origin.spoken.poll(10, TimeUnit.SECONDS))
            val flow = next()
            assertEquals(Passing.Http2, flow.passing)
            assertTrue(flow.tunnelled)
            assertNull(flow.failure)
            assertEquals("passed on unread: it speaks only HTTP/2", FlowLabels.passed(flow))
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
            assertEquals("/thing", flow.request.path)
        }
    }

    @Test
    fun aServerThatWontSpeakHttp2IsNamedAsTheReason() {
        // It ignores ALPN altogether, so the handshake succeeds and agrees on nothing.
        EchoServer().use { origin ->
            val port = proxy()
            through(port, origin.port, H2).use { app ->
                assertEquals(H2, app.applicationProtocol)
                // Hung up on: there is nobody at the other end it can talk to.
                assertEquals(-1, app.inputStream.read())
            }
            val flow = next()
            assertEquals(Passing.Http2, flow.passing)
            assertEquals("the app speaks only HTTP/2, and the server would not", flow.failure)
        }
    }

    @Test
    fun aServerThatRefusesHttp2SaysSoInWords() {
        EchoServer(HTTP_1_1).use { origin ->
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

    private companion object {
        /** What every HTTP/2 connection opens with, from RFC 9113. */
        val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".wire()

        fun String.wire() = toByteArray(Charsets.ISO_8859_1)
    }
}

/**
 * A TLS server of the test's own that sends back whatever it is sent, speaking [alpn] — or, given
 * none, paying ALPN no attention at all.
 */
private class EchoServer(private vararg val alpn: String) : AutoCloseable {
    private val server =
        TestAuthority.serverContext().serverSocketFactory.createServerSocket(0) as SSLServerSocket

    val port: Int
        get() = server.localPort

    /** The protocol each connection settled on, "" for none. */
    val spoken = LinkedBlockingQueue<String>()

    init {
        thread(isDaemon = true, name = "echo-origin") {
            while (true) {
                val socket =
                    try {
                        server.accept() as SSLSocket
                    } catch (_: IOException) {
                        return@thread
                    }
                thread(isDaemon = true) { echo(socket) }
            }
        }
    }

    private fun echo(socket: SSLSocket) {
        try {
            socket.use {
                if (alpn.isNotEmpty()) {
                    it.sslParameters = it.sslParameters.apply { applicationProtocols = alpn }
                }
                it.startHandshake()
                spoken += it.applicationProtocol.orEmpty()
                val buffer = ByteArray(1024)
                while (true) {
                    val read = it.inputStream.read(buffer)
                    if (read == -1) break
                    it.outputStream.write(buffer, 0, read)
                    it.outputStream.flush()
                }
            }
        } catch (_: IOException) {
            // The proxy hanging up is how every connection here ends.
        }
    }

    override fun close() = server.close()
}
