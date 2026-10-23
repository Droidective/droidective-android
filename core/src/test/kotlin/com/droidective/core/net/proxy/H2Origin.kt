package com.droidective.core.net.proxy

import com.droidective.core.net.Loopback
import com.droidective.core.net.h2.FrameCutter
import com.droidective.core.net.h2.H2Wire.data
import com.droidective.core.net.h2.H2Wire.frame
import com.droidective.core.net.h2.H2Wire.headers
import com.droidective.core.net.h2.HpackDecoder
import com.droidective.core.net.h2.Http2Exception
import com.droidective.core.net.h2.Http2Frame
import com.droidective.core.net.http.Header
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * A TLS server of the test's own speaking [alpn] — or, given none, paying ALPN no attention. When
 * it speaks HTTP/2 it reads frames, not fields, and answers each stream the app ends with [answer]:
 * so it answers even what the proxy can't make sense of.
 */
internal class H2Origin(private vararg val alpn: String) : AutoCloseable {
    private val server =
        TestAuthority.serverContext()
            .serverSocketFactory
            .createServerSocket(0, Loopback.BACKLOG, Loopback.localhost) as SSLServerSocket

    val port: Int
        get() = server.localPort

    /** Each stream's opening fields, as far as they read as a header block, in the order sent. */
    val asked = CopyOnWriteArrayList<List<Header>>()

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
                out.write(frame(Http2Frame.SETTINGS, 0, 0))
                out.flush()
                val decoder = HpackDecoder()
                val frames =
                    FrameCutter(preface = true) { frame ->
                        if (frame.type == Http2Frame.HEADERS) heard(decoder, frame)
                        val ends = frame.type == Http2Frame.DATA || frame.type == Http2Frame.HEADERS
                        if (ends && frame.has(Http2Frame.END_STREAM)) {
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

    /** A block whole in its HEADERS frame, unpadded, as every client here sends one. */
    private fun heard(decoder: HpackDecoder, frame: Http2Frame) {
        try {
            asked += decoder.decode(frame.payload)
        } catch (_: Http2Exception) {
            // What a test sent to be nonsense stays nonsense; it is answered all the same.
        }
    }

    override fun close() = server.close()
}
