package com.droidective.core.net.proxy

import com.droidective.core.net.http.BodyFraming
import com.droidective.core.net.http.HttpBody
import com.droidective.core.net.http.HttpReader
import com.droidective.core.net.tls.ProxyCertificates
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The proxy against real sockets: a server of our own on loopback, a client that goes through the
 * proxy to reach it, and the record that comes out the other side.
 */
class ProxyServerTest {
    private val sink = Collected()
    private var server: ProxyServer? = null

    @AfterTest
    fun stopTheProxy() {
        server?.stop()
    }

    private fun proxy(
        rules: ProxyRules = ProxyRules(),
        owners: FlowOwners = FlowOwners.UNKNOWN,
    ): Int {
        val running = ProxyServer(certificates, sink, trustingUpstream, owners)
        running.rules = rules
        server = running
        return running.start()
    }

    @Test
    fun itListensOnThePhoneAndNowhereElse() {
        proxy()
        // Anything wider would be an open proxy for everyone else on the Wi-Fi.
        val listening = assertNotNull(server?.address)
        assertTrue(listening.isLoopbackAddress, "the proxy was listening on $listening")
    }

    @Test
    fun aPlainRequestIsForwardedAndRecorded() {
        FakeServer().use { origin ->
            origin.reply = { ok("hello") }
            val port = proxy()
            assertEquals("hello", get("http://127.0.0.1:${origin.port}/thing?x=1", port))

            val flow = sink.next()
            assertEquals("GET", flow.request.method)
            assertEquals("127.0.0.1", flow.host)
            assertEquals(origin.port, flow.port)
            assertEquals("/thing?x=1", flow.request.path)
            assertEquals(200, flow.status)
            assertFalse(flow.secure)
            assertContentEquals("hello".toByteArray(), assertNotNull(flow.responseBody).bytes)
            assertNotNull(flow.durationMillis)
            // The server is handed a path, never the whole URL the proxy was given.
            assertEquals("/thing?x=1", origin.received.single().target)
        }
    }

    @Test
    fun aFlowCarriesTheAppThatOpenedTheConnection() {
        FakeServer().use { origin ->
            val port = proxy(owners = FlowOwners { 10144 })
            get("http://127.0.0.1:${origin.port}/thing", port)
            assertEquals(10144, sink.next().uid)
        }
    }

    @Test
    fun aFlowSaysNothingAboutTheAppWhenNothingCanBeTold() {
        FakeServer().use { origin ->
            val port = proxy()
            get("http://127.0.0.1:${origin.port}/thing", port)
            assertNull(sink.next().uid)
        }
    }

    @Test
    fun aRequestBodyIsDeliveredAndRecorded() {
        FakeServer().use { origin ->
            val port = proxy()
            val answer = post("http://127.0.0.1:${origin.port}/send", port, "{\"hello\":\"world\"}")
            assertEquals("hi", answer)

            assertEquals("{\"hello\":\"world\"}", origin.received.single().body)
            val flow = sink.next()
            assertContentEquals(
                "{\"hello\":\"world\"}".toByteArray(),
                flow.requestBody.bytes,
            )
        }
    }

    @Test
    fun theProxyPassesOnTheServersOwnHeadersUntouched() {
        FakeServer().use { origin ->
            origin.reply = {
                "HTTP/1.1 418 I am a teapot\r\nX-Brew: tea\r\nContent-Length: 0\r\n\r\n"
            }
            val port = proxy()
            val connection = connect("http://127.0.0.1:${origin.port}/brew", port)
            assertEquals(418, connection.responseCode)
            assertEquals("tea", connection.getHeaderField("X-Brew"))
            assertEquals(418, sink.next().status)
        }
    }

    @Test
    fun aChunkedAnswerIsRecordedAsItsContentRatherThanItsFraming() {
        FakeServer().use { origin ->
            origin.reply = {
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
            }
            val port = proxy()
            assertEquals("hello world", get("http://127.0.0.1:${origin.port}/stream", port))
            assertContentEquals(
                "hello world".toByteArray(),
                assertNotNull(sink.next().responseBody).bytes,
            )
        }
    }

    @Test
    fun twoRequestsCanShareOneConnection() {
        FakeServer().use { origin ->
            val port = proxy()
            Socket("127.0.0.1", port).use { socket ->
                val reader = HttpReader(socket.getInputStream())
                repeat(2) { index ->
                    origin.reply = { ok("body$index") }
                    val target = "http://127.0.0.1:${origin.port}/call$index"
                    socket
                        .getOutputStream()
                        .write(
                            wire("GET $target HTTP/1.1\r\nHost: 127.0.0.1:${origin.port}\r\n\r\n")
                        )
                    socket.getOutputStream().flush()
                    val head = assertNotNull(reader.readResponse())
                    val body =
                        HttpBody.relay(BodyFraming.of(head, "GET"), socket.getInputStream(), null)
                    assertEquals("body$index", String(body.bytes))
                }
            }
            assertEquals(listOf("/call0", "/call1"), origin.received.map { it.target })
            // One connection to the proxy, and one on from it.
            assertEquals(1, origin.connections.get())
            assertEquals(2, sink.all(count = 2).size)
        }
    }

    @Test
    fun aServerThatCannotBeReachedIsAnswered502AndStillRecorded() {
        val closed = ServerSocket(0).use { it.localPort }
        val port = proxy()
        Socket("127.0.0.1", port).use { socket ->
            socket
                .getOutputStream()
                .write(
                    wire(
                        "GET http://127.0.0.1:$closed/gone HTTP/1.1\r\nHost: 127.0.0.1:$closed\r\n\r\n"
                    )
                )
            socket.getOutputStream().flush()
            assertEquals(
                502,
                assertNotNull(HttpReader(socket.getInputStream()).readResponse()).status,
            )
        }
        val flow = sink.next()
        assertNull(flow.status)
        assertNotNull(flow.failure)
        assertEquals("/gone", flow.request.path)
    }

    @Test
    fun anOpenedTlsExchangeIsReadInTheClear() {
        secureServer().use { origin ->
            origin.reply = { ok("secret") }
            val port = proxy()
            assertEquals("secret", throughTunnel(port, origin.port, "/private"))

            val flow = sink.next()
            assertTrue(flow.secure)
            assertFalse(flow.tunnelled)
            assertEquals("/private", flow.request.path)
            assertEquals("https://localhost:${origin.port}/private", flow.url)
            assertEquals(200, flow.status)
            assertContentEquals("secret".toByteArray(), assertNotNull(flow.responseBody).bytes)
            // It really was read: the server saw a request, and the proxy wrote down its content.
            assertEquals("/private", origin.received.single().target)
        }
    }

    @Test
    fun aHostLeftAloneIsPassedThroughWithoutBeingRead() {
        secureServer().use { origin ->
            origin.reply = { ok("secret") }
            val port = proxy(ProxyRules(except = listOf("localhost")))
            assertEquals("secret", throughTunnel(port, origin.port, "/private"))

            val flow = sink.next()
            assertTrue(flow.tunnelled)
            assertNull(flow.response)
            assertNull(flow.status)
            // Nothing of what crossed was kept, which is the whole point of leaving it alone.
            assertTrue(flow.requestBody.isEmpty)
            assertEquals("https://localhost:${origin.port}", flow.url)
        }
    }

    /** CONNECT to the proxy, then TLS inside it, then one request — the client's whole job. */
    private fun throughTunnel(proxyPort: Int, originPort: Int, path: String): String {
        val socket = Socket("127.0.0.1", proxyPort)
        val target = "localhost:$originPort"
        socket.getOutputStream().write(wire("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n"))
        socket.getOutputStream().flush()
        // Read the proxy's answer off the raw stream: anything buffered past it would be TLS.
        val established = assertNotNull(HttpReader(socket.getInputStream()).readResponse())
        assertEquals(200, established.status)

        val tls =
            trust.socketFactory.createSocket(socket, "localhost", originPort, true) as SSLSocket
        return tls.use {
            it.startHandshake()
            it.outputStream.write(wire("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n"))
            it.outputStream.flush()
            val head = assertNotNull(HttpReader(it.inputStream).readResponse())
            String(HttpBody.relay(BodyFraming.of(head, "GET"), it.inputStream, null).bytes)
        }
    }

    private fun secureServer() =
        FakeServer(
            ProxyTls.serverContext(
                certificates.certificateFor("localhost"),
                certificates.caCertificate,
            )
        )

    private fun connect(url: String, proxyPort: Int): HttpURLConnection {
        val through = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
        return URI(url).toURL().openConnection(through) as HttpURLConnection
    }

    private fun get(url: String, proxyPort: Int): String =
        connect(url, proxyPort).inputStream.use { String(it.readBytes()) }

    private fun post(url: String, proxyPort: Int, body: String): String {
        val connection = connect(url, proxyPort)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.toByteArray().size)
        connection.outputStream.use { it.write(wire(body)) }
        return connection.inputStream.use { String(it.readBytes()) }
    }

    private companion object {
        /** Generating keys is slow, so every test in the class shares one authority. */
        val certificates: ProxyCertificates by lazy { ProxyCertificates.create("Droidective Test") }

        /**
         * A client, and the proxy's own upstream, that trust the test authority and nothing else.
         */
        val trust: SSLContext by lazy {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null)
            store.setCertificateEntry("ca", certificates.caCertificate)
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        }

        val trustingUpstream = UpstreamTls { origin, timeout ->
            val plain = Socket()
            plain.connect(InetSocketAddress(origin.host, origin.port), timeout)
            val socket =
                trust.socketFactory.createSocket(plain, origin.host, origin.port, true) as SSLSocket
            socket.sslParameters =
                socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    applicationProtocols = arrayOf("http/1.1")
                }
            socket.startHandshake()
            socket
        }

        fun ok(body: String) = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"

        fun wire(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    }
}

/** What the proxy recorded, waited on rather than slept for. */
private class Collected : FlowSink {
    private val done = LinkedBlockingQueue<Flow>()
    private val everything = CopyOnWriteArrayList<Flow>()
    val begun = CopyOnWriteArrayList<Flow>()

    override fun started(flow: Flow) {
        begun += flow
    }

    override fun finished(flow: Flow) {
        everything += flow
        done += flow
    }

    fun next(): Flow = done.poll(10, TimeUnit.SECONDS) ?: error("the proxy recorded nothing")

    /** Waits for [count] exchanges to finish, then hands back every one of them. */
    fun all(count: Int): List<Flow> {
        repeat(count) { next() }
        return everything
    }
}

/** One request a server of ours was asked. */
private data class Asked(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: String,
)

/** A server on loopback that answers whatever [reply] says, plain or over TLS. */
private class FakeServer(context: SSLContext? = null) : AutoCloseable {
    private val server: ServerSocket =
        context?.serverSocketFactory?.createServerSocket(0) ?: ServerSocket(0)

    val port: Int
        get() = server.localPort

    val received = CopyOnWriteArrayList<Asked>()
    val connections = AtomicInteger()

    @Volatile var reply: (Asked) -> String = { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi" }

    init {
        thread(isDaemon = true, name = "fake-origin") {
            while (true) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                connections.incrementAndGet()
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                while (true) {
                    val asked = read(input) ?: return
                    received += asked
                    out.write(reply(asked).toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
            }
        } catch (_: IOException) {
            // A client hanging up is how these connections normally end.
        }
    }

    private fun read(input: InputStream): Asked? {
        val first = line(input) ?: return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val next = line(input) ?: return null
            if (next.isEmpty()) break
            val colon = next.indexOf(':')
            headers[next.substring(0, colon).lowercase()] = next.substring(colon + 1).trim()
        }
        val parts = first.split(' ')
        return Asked(parts[0], parts[1], headers, body(input, headers))
    }

    private fun body(input: InputStream, headers: Map<String, String>): String {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val next = input.read(bytes, read, length - read)
            if (next == -1) break
            read += next
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return null
            if (byte == '\n'.code) {
                return String(out.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r")
            }
            out.write(byte)
        }
    }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
            // Nothing to do about a listener that will not close.
        }
    }
}
