package com.droidective.core.net.proxy

import com.droidective.core.net.http.BodyFraming
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.HttpBody
import com.droidective.core.net.http.HttpReader
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import com.droidective.core.net.tls.ProxyCertificates
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * An HTTP proxy that writes down what goes through it.
 *
 * A device pointed at this sends every request here first. A plain request is forwarded and
 * recorded. A `CONNECT` either opens — the proxy answers as the host, using a certificate minted by
 * [certificates], and reads the exchange inside — or is passed through as bytes when [rules] says
 * that host is left alone.
 *
 * What it will not do is change what the app sees. Bodies are forwarded whole however much of them
 * is kept, the server's own certificate is checked before anything is believed, and a host that is
 * passed through is passed through untouched.
 */
class ProxyServer(
    private val certificates: ProxyCertificates,
    private val sink: FlowSink = FlowSink.NONE,
    private val upstream: UpstreamTls = UpstreamTls.SYSTEM,
    private val owners: FlowOwners = FlowOwners.UNKNOWN,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Which hosts are opened; may be changed while the proxy is running. */
    @Volatile var rules: ProxyRules = ProxyRules()

    private val ids = AtomicLong()
    private val contexts = ConcurrentHashMap<String, SSLContext>()
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private var listener: ServerSocket? = null
    private var scope: CoroutineScope? = null

    /** The port being listened on, or -1 when stopped. */
    val port: Int
        get() = listener?.localPort ?: -1

    /** The address being listened on, which is always the phone's own. */
    val address: InetAddress?
        get() = listener?.inetAddress

    /**
     * Starts listening, on loopback only. Port 0 takes whatever is free, which is what the tests
     * use.
     *
     * Loopback is not a detail. A proxy listening on every interface is an open proxy for everyone
     * else on that Wi-Fi: their traffic through this phone, and this phone's address against
     * whatever they do with it. The phone points at 127.0.0.1 and nothing off the device can reach
     * it.
     *
     * It is IPv4's loopback by name, not `InetAddress.getLoopbackAddress()`: on Android that is
     * IPv6's `::1`, and a socket bound there never hears a connection to 127.0.0.1.
     */
    fun start(port: Int = 0): Int {
        check(listener == null) { "the proxy is already listening" }
        val server = ServerSocket(port, BACKLOG, LOOPBACK)
        val running = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listener = server
        scope = running
        running.launch {
            while (isActive) {
                val client =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        break // The listener was closed: that is how stopping looks from here.
                    }
                open += client
                launch {
                    try {
                        serve(client)
                    } finally {
                        open -= client
                        client.closeQuietly()
                    }
                }
            }
        }
        return server.localPort
    }

    /** Stops listening and drops every connection, including the ones parked in a tunnel. */
    fun stop() {
        listener?.closeQuietly()
        listener = null
        open.forEach { it.closeQuietly() }
        open.clear()
        scope?.cancel()
        scope = null
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = IDLE_MILLIS
            val source = BufferedInputStream(client.getInputStream())
            val out = BufferedOutputStream(client.getOutputStream())
            val head = HttpReader(source).readRequest() ?: return
            // Asked once per connection: the port is the only thing tying it to an app, and it
            // stops meaning anything the moment the connection closes.
            val uid = owners.uidOf(client.port)
            if (head.method.equals(CONNECT, ignoreCase = true)) {
                accept(head, client, source, out, uid)
            } else {
                exchanges(source, out, secure = false, fixed = null, first = head, uid = uid)
            }
        } catch (_: IOException) {
            // A client that goes away part-way through is ordinary and not worth a record.
        }
    }

    /** A `CONNECT`: answer as the host and read what follows, or pass the bytes through. */
    private fun accept(
        head: RequestHead,
        client: Socket,
        source: InputStream,
        out: OutputStream,
        uid: Int?,
    ) {
        val origin = Origin.authority(head.target) ?: return out.writeQuietly(BAD_REQUEST)
        out.write(ESTABLISHED)
        out.flush()
        if (!rules.opens(origin.host)) return tunnel(origin, head, client, source, out, uid)
        val context =
            contexts.getOrPut(origin.host) {
                val leaf = certificates.certificateFor(origin.host, clock())
                ProxyTls.serverContext(leaf, certificates.caCertificate)
            }
        val tls = ProxyTls.accept(context, client, origin)
        try {
            tls.startHandshake()
        } catch (e: SSLException) {
            // The app would not have the certificate: either the authority is not installed, or
            // the app pins its own. Saying so beats a connection that merely stops.
            sink.finished(
                begin(head, origin, secure = true, uid = uid)
                    .copy(
                        finishedAt = clock(),
                        failure = "the app refused the certificate: ${e.reason()}",
                    )
            )
            return
        }
        exchanges(
            BufferedInputStream(tls.inputStream),
            BufferedOutputStream(tls.outputStream),
            secure = true,
            fixed = origin,
            first = null,
            uid = uid,
        )
    }

    /** Requests on one connection, one after another, for as long as both ends allow. */
    private fun exchanges(
        source: InputStream,
        out: OutputStream,
        secure: Boolean,
        fixed: Origin?,
        first: RequestHead?,
        uid: Int?,
    ) {
        val reader = HttpReader(source)
        var upstream: Upstream? = null
        var head = first ?: reader.readRequest()
        try {
            while (head != null) {
                val origin = fixed ?: Origin.of(head, secure) ?: break
                if (upstream != null && upstream.origin != origin) {
                    upstream.close()
                    upstream = null
                }
                val flow = begin(head, origin, secure, uid)
                if (upstream == null) {
                    upstream =
                        try {
                            Upstream(origin, dial(origin, secure))
                        } catch (e: IOException) {
                            return failed(flow, out, e)
                        }
                }
                if (!exchange(flow, head, source, out, upstream)) break
                head = reader.readRequest()
            }
        } finally {
            upstream?.close()
        }
    }

    /** One request and its answer. Returns whether the connection can carry another. */
    private fun exchange(
        flow: Flow,
        head: RequestHead,
        source: InputStream,
        out: OutputStream,
        upstream: Upstream,
    ): Boolean {
        upstream.sink.write(
            head.with(target = head.path, headers = forwardable(head.headers)).bytes()
        )
        val requestBody = HttpBody.relay(BodyFraming.of(head), source, upstream.sink)
        upstream.sink.flush()
        val asked = flow.copy(requestBody = requestBody)
        sink.started(asked)

        val response =
            try {
                upstream.reader.readResponse() ?: throw IOException("the server answered nothing")
            } catch (e: IOException) {
                failed(asked, out, e)
                return false
            }
        out.write(response.bytes())
        if (response.status == SWITCHING) return upgrade(asked, response, source, out, upstream)

        val framing = BodyFraming.of(response, head.method)
        val body = HttpBody.relay(framing, upstream.source, out)
        out.flush()
        sink.finished(asked.copy(response = response, responseBody = body, finishedAt = clock()))
        return framing != BodyFraming.UntilClose &&
            !closes(head.headers) &&
            !closes(response.headers)
    }

    /**
     * A 101: from here the connection is whatever the two ends agreed on — a WebSocket, usually —
     * and no longer a sequence of requests, so the rest is carried across as bytes.
     */
    private fun upgrade(
        flow: Flow,
        response: ResponseHead,
        source: InputStream,
        out: OutputStream,
        upstream: Upstream,
    ): Boolean {
        out.flush()
        bothWays(source, out, upstream.source, upstream.sink)
        sink.finished(flow.copy(response = response, finishedAt = clock(), tunnelled = true))
        return false
    }

    /** A host left alone: the bytes cross untouched and only that it happened is recorded. */
    private fun tunnel(
        origin: Origin,
        head: RequestHead,
        client: Socket,
        source: InputStream,
        out: OutputStream,
        uid: Int?,
    ) {
        val flow = begin(head, origin, secure = true, uid = uid).copy(tunnelled = true)
        sink.started(flow)
        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress(origin.host, origin.port), CONNECT_MILLIS)
            // A tunnel may be idle for a long time and still be alive; only a close ends it.
            client.soTimeout = 0
            upstream.soTimeout = 0
            bothWays(source, out, upstream.getInputStream(), upstream.getOutputStream())
            sink.finished(flow.copy(finishedAt = clock()))
        } catch (e: IOException) {
            sink.finished(flow.copy(finishedAt = clock(), failure = e.reason()))
        } finally {
            upstream.closeQuietly()
        }
    }

    private fun begin(head: RequestHead, origin: Origin, secure: Boolean, uid: Int?) =
        Flow(ids.incrementAndGet(), clock(), secure, origin.host, origin.port, head, uid = uid)

    private fun failed(flow: Flow, out: OutputStream, error: Throwable) {
        sink.finished(flow.copy(finishedAt = clock(), failure = error.reason()))
        out.writeQuietly(badGateway(error))
    }

    private fun dial(origin: Origin, secure: Boolean): Socket =
        if (secure) {
            upstream.connect(origin, CONNECT_MILLIS)
        } else {
            Socket().apply {
                connect(InetSocketAddress(origin.host, origin.port), CONNECT_MILLIS)
                soTimeout = IDLE_MILLIS
            }
        }

    /** The server end of one client connection, kept while the requests keep going there. */
    private class Upstream(val origin: Origin, private val socket: Socket) {
        val source = BufferedInputStream(socket.getInputStream())
        val sink = BufferedOutputStream(socket.getOutputStream())
        val reader = HttpReader(source)

        fun close() = socket.closeQuietly()
    }

    private companion object {
        const val CONNECT = "CONNECT"
        const val SWITCHING = 101
        const val BACKLOG = 50
        const val IDLE_MILLIS = 120_000
        const val CONNECT_MILLIS = 15_000

        /** What the phone's proxy setting names. */
        val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

        val ESTABLISHED = wire("HTTP/1.1 200 Connection Established\r\n\r\n")
        val BAD_REQUEST = wire("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")

        /**
         * The headers that name this hop rather than the message. They are answered here and must
         * not travel on. `Transfer-Encoding` stays: the relay passes a chunked body along in the
         * shape it arrived, and the far side has to be told that.
         */
        val PER_HOP =
            arrayOf(
                "Connection",
                "Proxy-Connection",
                "Keep-Alive",
                "Proxy-Authenticate",
                "Proxy-Authorization",
                "TE",
            )

        fun forwardable(headers: Headers): Headers =
            // An upgrade is negotiated with the very headers that otherwise stop here, so a
            // request asking for one keeps them and the two ends settle it between themselves.
            if (headers.has("Upgrade")) headers.without("Proxy-Connection", "Proxy-Authorization")
            else headers.without(*PER_HOP, "Upgrade")

        fun closes(headers: Headers): Boolean =
            headers.values("Connection").any { value ->
                value.split(',').any { it.trim().equals("close", ignoreCase = true) }
            }

        fun badGateway(error: Throwable): ByteArray {
            val body = "Droidective could not reach the server: ${error.reason()}"
            return wire(
                "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
            )
        }

        fun wire(text: String) = text.toByteArray(StandardCharsets.ISO_8859_1)
    }
}

/** Copies both ways at once and returns when either side is done. */
private fun bothWays(
    clientIn: InputStream,
    clientOut: OutputStream,
    serverIn: InputStream,
    serverOut: OutputStream,
) {
    val back = thread(name = "droidective-proxy-tunnel") { copyQuietly(serverIn, clientOut) }
    copyQuietly(clientIn, serverOut)
    back.join()
}

private fun copyQuietly(source: InputStream, sink: OutputStream) {
    try {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read == -1) break
            sink.write(buffer, 0, read)
            sink.flush()
        }
    } catch (_: IOException) {
        // Either end closing is how a tunnel ends; there is nothing to report about it.
    }
}

private fun OutputStream.writeQuietly(bytes: ByteArray) {
    try {
        write(bytes)
        flush()
    } catch (_: IOException) {
        // The client is already gone, which is why we were telling it something went wrong.
    }
}

private fun Socket.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Closing is the one thing there is no recovering from and nothing to say about.
    }
}

private fun ServerSocket.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // As above.
    }
}

private fun Throwable.reason(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
