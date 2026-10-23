package com.droidective.core.net.proxy

import com.droidective.core.net.grpc.Grpc
import com.droidective.core.net.h2.Http2Client
import com.droidective.core.net.http.BodyFraming
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.HTTP_1_1
import com.droidective.core.net.http.HTTP_2
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.HttpBody
import com.droidective.core.net.http.HttpReader
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * A connection to [server] speaking HTTP/2: over TLS with `h2` agreed, or in the clear to a server
 * taking HTTP/2 from the first byte, as a gRPC server in development does.
 *
 * @throws IOException when it can't be reached, or won't agree to `h2`, which gRPC needs.
 */
internal fun openHttp2(upstream: UpstreamTls, server: Server): Socket {
    val socket =
        if (server.secure) upstream.connect(server.origin, HTTP2_CONNECT_MILLIS, ProxyTls.H2)
        else Dial.connect(server.origin.host, server.origin.port, HTTP2_CONNECT_MILLIS)
    if (socket is SSLSocket && socket.applicationProtocol != ProxyTls.H2) {
        socket.close()
        throw IOException("the server would not speak HTTP/2, which gRPC needs")
    }
    socket.soTimeout = HTTP2_IDLE_MILLIS
    return socket
}

private const val HTTP2_CONNECT_MILLIS = 15_000
private const val HTTP2_IDLE_MILLIS = 60_000

/** A request ready to go out again: where to, the head as the server will see it, the body. */
class Outgoing(
    val secure: Boolean,
    val origin: Origin,
    val head: RequestHead,
    val body: ByteArray,
) {
    /** Where it is aimed, as a remap is matched against. */
    val url: String
        get() = Server(secure, origin).url + head.target
}

/** Whether a flow's request can be sent again, and if not, why not. */
sealed interface Repeat {
    data class Ready(val outgoing: Outgoing) : Repeat

    data class Refused(val reason: String) : Repeat
}

/**
 * A request sent again from the phone and recorded like any other: Charles's *Repeat*.
 *
 * It goes straight to the server rather than back through the proxy, so the rules — an app not
 * being read, a host passed through — can't turn the one request somebody asked to see into one
 * nobody can read. The server's certificate is checked, as the proxy checks it.
 */
class Repeater(
    private val upstream: UpstreamTls = UpstreamTls.SYSTEM,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Sends [outgoing] and returns the exchange, as flow [id], a repeat of flow [of]. Sent [via]
     * another server when a remap says so, as the app's own request would be.
     */
    fun send(outgoing: Outgoing, id: Long, of: Long?, via: Server? = null): Flow {
        val body = outgoing.body
        val flow =
            Flow(
                id = id,
                startedAt = clock(),
                secure = outgoing.secure,
                host = outgoing.origin.host,
                port = outgoing.origin.port,
                request = outgoing.head,
                requestBody = CapturedBody(body, body.size.toLong(), truncated = false),
                repeatOf = of,
                sentTo = via?.url,
            )
        val server = via ?: Server(outgoing.secure, outgoing.origin)
        val head = via?.hosting(outgoing.head) ?: outgoing.head
        return try {
            if (head.version == HTTP_2) {
                http2(flow, server, head, body)
            } else {
                open(server, ProxyTls.HTTP_1_1).use { exchange(flow, head, body, it) }
            }
        } catch (e: SSLException) {
            flow.copy(finishedAt = clock(), failure = TlsFailure.upstream(e.message))
        } catch (e: IOException) {
            flow.copy(finishedAt = clock(), failure = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun open(server: Server, protocol: String): Socket =
        if (server.secure) upstream.connect(server.origin, CONNECT_MILLIS, protocol)
        else Dial.connect(server.origin.host, server.origin.port, CONNECT_MILLIS)

    /** The call as one stream, on an HTTP/2 connection of its own. */
    private fun http2(flow: Flow, server: Server, head: RequestHead, body: ByteArray): Flow =
        openHttp2(upstream, server).use { socket ->
            val answer =
                Http2Client(socket.getInputStream(), socket.getOutputStream())
                    .send(fields(head, server), body)
            val status =
                answer.fields.firstOrNull { it.name == STATUS }?.value?.toIntOrNull()
                    ?: throw IOException(NO_STATUS)
            val headers = Headers(answer.fields.filterNot { it.name.startsWith(':') })
            flow.copy(
                response = ResponseHead(HTTP_2, status, "", headers),
                responseBody = answer.body,
                trailers = Headers(answer.trailers),
                finishedAt = clock(),
            )
        }

    /** [head] as HTTP/2 carries it: what the request line and `Host` said, as pseudo-headers. */
    private fun fields(head: RequestHead, server: Server): List<Header> =
        listOf(
            Header(":method", head.method),
            Header(":scheme", if (server.secure) "https" else "http"),
            Header(":authority", head.headers[Headers.HOST] ?: server.authority),
            Header(":path", head.target),
        ) + head.headers.without(Headers.HOST).all.map { Header(it.name.lowercase(), it.value) }

    private fun exchange(flow: Flow, head: RequestHead, body: ByteArray, socket: Socket): Flow {
        socket.soTimeout = IDLE_MILLIS
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(head.bytes())
        out.write(body)
        out.flush()
        val input = BufferedInputStream(socket.getInputStream())
        val response = HttpReader(input).readResponse() ?: throw IOException(NOTHING)
        // A 101 hands the connection over to something else, which there is no one here to speak.
        val answer =
            if (response.status == SWITCHING) CapturedBody.NONE
            else HttpBody.relay(BodyFraming.of(response, flow.request.method), input, null)
        return flow.copy(response = response, responseBody = answer, finishedAt = clock())
    }

    companion object {
        /**
         * [flow]'s request as it can go out again, or why it can't: a connection never read has no
         * request in it, and a body kept only in part would send something else.
         *
         * The head is the one the server saw — a path, not the whole URL a proxy is handed — with
         * the hop's own headers left out and the length stated, since the body goes in one piece
         * however it was framed the first time. It goes as HTTP/1.1 even when the app spoke HTTP/2,
         * except a gRPC call, which only HTTP/2 can carry.
         */
        fun prepare(flow: Flow): Repeat {
            if (flow.opening) return Repeat.Refused(NEVER_READ)
            if (flow.socket) return Repeat.Refused(SOCKET)
            if (flow.requestBody.truncated) return Repeat.Refused(CLIPPED)
            val body = flow.requestBody.bytes
            if (Grpc.carries(flow.request.headers.contentType))
                return Repeat.Ready(grpc(flow, body))
            val head =
                flow.request
                    .with(
                        target = flow.request.path,
                        headers = sendable(flow.request.headers, flow.authority, body.size),
                    )
                    .copy(version = HTTP_1_1)
            return Repeat.Ready(Outgoing(flow.secure, Origin(flow.host, flow.port), head, body))
        }

        /**
         * A gRPC call on HTTP/2, its messages as they were framed: its headers less the ones HTTP/2
         * has no place for, and `te: trailers`, which a gRPC server may refuse a call without.
         */
        private fun grpc(flow: Flow, body: ByteArray): Outgoing {
            var headers = flow.request.headers.without(*Headers.HOP_BY_HOP, Headers.CONTENT_LENGTH)
            if (!headers.has(Headers.HOST)) {
                headers = Headers(listOf(Header(Headers.HOST, flow.authority)) + headers.all)
            }
            val head =
                flow.request
                    .with(target = flow.request.path, headers = headers.add(TE, TRAILERS))
                    .copy(version = HTTP_2)
            return Outgoing(flow.secure, Origin(flow.host, flow.port), head, body)
        }

        private fun sendable(headers: Headers, authority: String, length: Int): Headers {
            var sent = headers.without(*Headers.HOP_BY_HOP, Headers.CONTENT_LENGTH)
            readable(headers[ACCEPT_ENCODING])?.let {
                sent = sent.without(ACCEPT_ENCODING).add(ACCEPT_ENCODING, it)
            }
            if (!sent.has(Headers.HOST)) {
                sent = Headers(listOf(Header(Headers.HOST, authority)) + sent.all)
            }
            if (length > 0 || headers.has(Headers.CONTENT_LENGTH)) {
                sent = sent.add(Headers.CONTENT_LENGTH, length.toString())
            }
            // One request and its answer: the connection is closed after it either way.
            return sent.add(CONNECTION, CLOSE)
        }

        /**
         * The codings asked for, less the ones whose answer couldn't be read here, or null when
         * nothing needs leaving out. A browser asks for dictionary-compressed answers (`dcb`,
         * `dcz`), and a repeat whose answer comes back in one shows nothing; a repeat is sent to be
         * looked at.
         */
        internal fun readable(accepted: String?): String? {
            if (accepted == null) return null
            val codings = accepted.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val kept = codings.filter { coding ->
                coding.substringBefore(';').trim().lowercase() in DECODABLE
            }
            if (kept.size == codings.size) return null
            return kept.ifEmpty { listOf(IDENTITY) }.joinToString(", ")
        }

        private const val ACCEPT_ENCODING = "Accept-Encoding"
        private const val IDENTITY = "identity"
        private val DECODABLE = setOf("gzip", "x-gzip", "deflate", "br", "zstd", IDENTITY, "*")
        private const val CONNECTION = "Connection"
        private const val CLOSE = "close"
        private const val SWITCHING = 101
        private const val CONNECT_MILLIS = 15_000
        private const val IDLE_MILLIS = 60_000
        private const val NOTHING = "the server answered nothing"
        private const val SOCKET =
            "A socket was a conversation, not a request: sent again, it would only ask to start one."
        private const val NEVER_READ = "Nothing was read on this connection, so there's no request."
        private const val CLIPPED =
            "Only part of the body was kept, so sending it again would send something else."
        private const val TE = "te"
        private const val TRAILERS = "trailers"
        private const val STATUS = ":status"
        private const val NO_STATUS = "the server answered with no status"
    }
}
