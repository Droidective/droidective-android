package com.droidective.core.net.proxy

import com.droidective.core.net.http.BodyFraming
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.HTTP_1_1
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.HttpBody
import com.droidective.core.net.http.HttpReader
import com.droidective.core.net.http.RequestHead
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import javax.net.ssl.SSLException

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
            open(server).use { exchange(flow, head, body, it) }
        } catch (e: SSLException) {
            flow.copy(finishedAt = clock(), failure = TlsFailure.upstream(e.message))
        } catch (e: IOException) {
            flow.copy(finishedAt = clock(), failure = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun open(server: Server): Socket =
        if (server.secure) upstream.connect(server.origin, CONNECT_MILLIS, ProxyTls.HTTP_1_1)
        else Dial.connect(server.origin.host, server.origin.port, CONNECT_MILLIS)

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
         * however it was framed the first time. It goes as HTTP/1.1 even when the app spoke HTTP/2.
         */
        fun prepare(flow: Flow): Repeat {
            if (flow.opening) return Repeat.Refused(NEVER_READ)
            if (flow.socket) return Repeat.Refused(SOCKET)
            if (flow.requestBody.truncated) return Repeat.Refused(CLIPPED)
            val body = flow.requestBody.bytes
            val head =
                flow.request
                    .with(
                        target = flow.request.path,
                        headers = sendable(flow.request.headers, flow.authority, body.size),
                    )
                    .copy(version = HTTP_1_1)
            return Repeat.Ready(Outgoing(flow.secure, Origin(flow.host, flow.port), head, body))
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
         * nothing needs leaving out. A browser asks for zstd, and a repeat whose answer comes back
         * in it shows nothing; a repeat is sent to be looked at.
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
        private val DECODABLE = setOf("gzip", "x-gzip", "deflate", "br", IDENTITY, "*")
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
    }
}
