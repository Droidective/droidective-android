package com.droidective.core.net.h2

import com.droidective.core.net.h2.H2Wire.CONTINUATION
import com.droidective.core.net.h2.H2Wire.DATA
import com.droidective.core.net.h2.H2Wire.END_HEADERS
import com.droidective.core.net.h2.H2Wire.END_STREAM
import com.droidective.core.net.h2.H2Wire.HEADERS
import com.droidective.core.net.h2.H2Wire.PADDED
import com.droidective.core.net.h2.H2Wire.PRIORITY
import com.droidective.core.net.h2.H2Wire.PUSH_PROMISE
import com.droidective.core.net.h2.H2Wire.SETTINGS
import com.droidective.core.net.h2.H2Wire.block
import com.droidective.core.net.h2.H2Wire.data
import com.droidective.core.net.h2.H2Wire.frame
import com.droidective.core.net.h2.H2Wire.headers
import com.droidective.core.net.h2.H2Wire.indexed
import com.droidective.core.net.h2.H2Wire.indexing
import com.droidective.core.net.h2.H2Wire.opening
import com.droidective.core.net.h2.H2Wire.reset
import com.droidective.core.net.http.Header
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Http2ReaderTest {
    private val said = Said()
    private val reader = Http2Reader(said)

    private fun app(vararg chunks: ByteArray) = chunks.forEach { reader.fromApp(it, it.size) }

    private fun server(vararg chunks: ByteArray) = chunks.forEach { reader.fromServer(it, it.size) }

    @Test
    fun aRequestAndItsAnswerAreOneExchange() {
        app(opening(), headers(1, GET, PATH_A, AUTHORITY, "accept" to "*/*"))
        server(
            frame(SETTINGS, 0, 0),
            headers(1, OK, "content-type" to "text/plain", end = false),
            data(1, "hello"),
        )
        val done = said.finished.single()
        assertEquals(Header("accept", "*/*"), done.request.last())
        assertEquals(Header(":status", "200"), assertNotNull(done.response).first())
        assertEquals("hello", String(assertNotNull(done.responseBody).bytes))
        assertTrue(done.requestBody.isEmpty)
        assertNull(done.reset)
        assertNull(done.cut)
        assertEquals(listOf("requested 1", "answered 1", "finished 1"), said.order)
    }

    @Test
    fun theAnswerCanBeShownBeforeTheRequest() {
        // Each direction is read on its own thread, after its bytes have gone on, so the answer
        // can be read first.
        server(headers(1, OK, end = false), data(1, "early"))
        assertTrue(said.order.isEmpty())
        app(opening(), headers(1, GET, PATH_A, AUTHORITY))
        val done = said.finished.single()
        assertEquals("early", String(assertNotNull(done.responseBody).bytes))
        assertEquals(listOf("requested 1", "finished 1"), said.order)
    }

    @Test
    fun aLaterStreamsAnswerFirstDoesNotLoseAnEarlierRequest() {
        // Both requests crossed in one read; the server answered the second before the first.
        server(headers(3, OK), headers(1, OK))
        app(opening() + headers(1, GET, PATH_A, AUTHORITY) + headers(3, GET, PATH_B, AUTHORITY))
        assertEquals(listOf(1, 3), said.finished.map { it.stream }.sorted())
    }

    @Test
    fun streamsInterleave() {
        app(opening(), headers(1, GET, PATH_A, AUTHORITY), headers(3, GET, PATH_B, AUTHORITY))
        server(
            headers(3, OK, end = false),
            headers(1, OK, end = false),
            data(1, "one, ", end = false),
            data(3, "three"),
            data(1, "two"),
        )
        val bodies = said.finished.associate { it.stream to String(it.responseBody!!.bytes) }
        assertEquals(mapOf(3 to "three", 1 to "one, two"), bodies)
    }

    @Test
    fun aRequestsBodyAndAnAnswersTrailers() {
        app(
            opening(),
            headers(1, POST, PATH_A, AUTHORITY, "content-type" to "application/grpc", end = false),
            data(1, "a=1", end = false),
            data(1, "&b=2"),
        )
        server(
            headers(1, OK, end = false),
            data(1, "x", end = false),
            headers(1, "grpc-status" to "0", "grpc-message" to "OK"),
        )
        val done = said.finished.single()
        assertEquals("a=1&b=2", String(done.requestBody.bytes))
        assertEquals(
            listOf(Header("grpc-status", "0"), Header("grpc-message", "OK")),
            done.trailers,
        )
    }

    @Test
    fun anInformationalAnswerIsNotTheAnswer() {
        app(opening(), headers(1, POST, PATH_A, AUTHORITY, end = false))
        server(headers(1, ":status" to "100", end = false))
        assertTrue("answered 1" !in said.order)
        app(data(1, "body"))
        server(headers(1, OK))
        assertEquals("200", said.finished.single().response?.first()?.value)
    }

    @Test
    fun aResetSaysWhoAndWhy() {
        app(opening(), headers(1, GET, PATH_A, AUTHORITY), headers(3, GET, PATH_B, AUTHORITY))
        server(reset(1, 7))
        app(reset(3, 8))
        val resets = said.finished.associate { it.stream to it.reset }
        assertEquals("the server reset it (refused)", resets[1])
        assertEquals("the app reset it (cancelled)", resets[3])
    }

    @Test
    fun aResetWithNoErrorIsNoFailure() {
        // A server that has its answer out may reset to stop an upload it doesn't want.
        app(opening(), headers(1, POST, PATH_A, AUTHORITY, end = false))
        server(headers(1, OK), reset(1, 0))
        val done = said.finished.single()
        assertNull(done.reset)
        assertEquals("200", done.response?.first()?.value)
    }

    @Test
    fun aBlockCarriesOnInContinuations() {
        val whole = block(GET, PATH_A, AUTHORITY, "x-long" to "yes")
        app(
            opening(),
            frame(HEADERS, END_STREAM, 1, whole.copyOfRange(0, 5)),
            frame(CONTINUATION, 0, 1, whole.copyOfRange(5, 9)),
            frame(CONTINUATION, END_HEADERS, 1, whole.copyOfRange(9, whole.size)),
        )
        server(headers(1, OK))
        assertEquals(Header("x-long", "yes"), said.finished.single().request.last())
    }

    @Test
    fun aBlockBrokenOffPartWayStopsTheReading() {
        app(
            opening(),
            frame(HEADERS, END_STREAM, 1, block(GET)),
            headers(3, GET, PATH_B, AUTHORITY),
        )
        assertEquals(listOf("a header block broken off part-way"), said.stopped)
    }

    @Test
    fun paddingAndPriorityAreLeftOut() {
        val padding = 3
        val fields = block(GET, PATH_A, AUTHORITY)
        val priority = byteArrayOf(0, 0, 0, 0, 16)
        val payload = byteArrayOf(padding.toByte()) + priority + fields + ByteArray(padding)
        app(opening(), frame(HEADERS, END_HEADERS or END_STREAM or PADDED or PRIORITY, 1, payload))
        server(
            headers(1, OK, end = false),
            frame(
                DATA,
                END_STREAM or PADDED,
                1,
                byteArrayOf(2) + "hi".toByteArray() + ByteArray(2),
            ),
        )
        val done = said.finished.single()
        assertEquals(Header(":path", "/a"), done.request[1])
        assertEquals("hi", String(assertNotNull(done.responseBody).bytes))
    }

    @Test
    fun aPushedStreamIsReadForTheTableAndNotKept() {
        app(opening(), headers(1, GET, PATH_A, AUTHORITY))
        // The promise adds to the server's table; the answer after it leans on that.
        val promised = byteArrayOf(0, 0, 0, 2) + indexing("x-pushed" to "yes")
        server(
            frame(PUSH_PROMISE, END_HEADERS, 1, promised),
            frame(HEADERS, END_HEADERS or END_STREAM, 1, block(OK) + indexed(62)),
            headers(2, OK, end = false),
            data(2, "pushed"),
        )
        val done = said.finished.single()
        assertEquals(1, done.stream)
        assertEquals(Header("x-pushed", "yes"), done.response?.last())
    }

    @Test
    fun theConnectionClosingCutsWhatIsStillOpen() {
        app(
            opening(),
            headers(1, GET, PATH_A, AUTHORITY),
            headers(3, POST, PATH_B, AUTHORITY, end = false),
        )
        server(headers(1, OK, end = false), data(1, "part", end = false))
        // An answer to a request never seen is not an exchange anyone can be shown.
        server(headers(5, OK, end = false))
        reader.closed()
        val cut = said.finished.associate { it.stream to it.cut }
        assertEquals(setOf(1, 3), cut.keys)
        assertEquals("the connection closed before it finished", cut[1])
    }

    @Test
    fun nonsenseStopsTheReadingAndSaysWhy() {
        app(opening(), headers(1, GET, PATH_A, AUTHORITY))
        server(frame(HEADERS, END_HEADERS, 1, indexed(0)))
        assertEquals(1, said.stopped.size)
        assertTrue("field 0" in said.stopped.single(), said.stopped.single())
        val cut = assertNotNull(said.finished.single().cut)
        assertTrue(cut.startsWith("stopped reading the connection: "), cut)
        // Whatever comes after is carried by the connection, and not read.
        app(headers(3, GET, PATH_B, AUTHORITY))
        server(headers(3, OK))
        reader.closed()
        assertEquals(1, said.finished.size)
    }

    @Test
    fun anAppThatDoesNotOpenWithThePrefaceIsNotReadAsHttp2() {
        val http1 = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        app(http1)
        assertEquals(listOf("no HTTP/2 preface"), said.stopped)
    }

    @Test
    fun howTheBytesAreSplitChangesNothing() {
        val fromApp =
            opening() + headers(1, POST, PATH_A, AUTHORITY, end = false) + data(1, "request body")
        val fromServer = headers(1, OK, "x-a" to "b", end = false) + data(1, "answer")
        for (byte in fromApp) app(byteArrayOf(byte))
        for (byte in fromServer) server(byteArrayOf(byte))
        val done = said.finished.single()
        assertEquals("request body", String(done.requestBody.bytes))
        assertEquals("answer", String(assertNotNull(done.responseBody).bytes))
    }

    @Test
    fun aBodyIsKeptOnlyUpToTheLimit() {
        val small = Said()
        val reader = Http2Reader(small, keep = 4)
        val fromApp = opening() + headers(1, GET, PATH_A, AUTHORITY)
        val fromServer = headers(1, OK, end = false) + data(1, "0123456789")
        reader.fromApp(fromApp, fromApp.size)
        reader.fromServer(fromServer, fromServer.size)
        val body = assertNotNull(small.finished.single().responseBody)
        assertEquals("0123", String(body.bytes))
        assertEquals(10, body.wireLength)
        assertTrue(body.truncated)
    }

    @Test
    fun framesForAStreamThatIsOverAreLeftAlone() {
        app(opening(), headers(1, GET, PATH_A, AUTHORITY))
        server(headers(1, OK), data(1, "late"), reset(1, 8))
        assertEquals(1, said.finished.size)
        assertTrue(said.stopped.isEmpty())
    }

    /** Everything the reader said, in order. */
    private class Said : Http2Events {
        val order = mutableListOf<String>()
        val finished = mutableListOf<Http2Exchange>()
        val stopped = mutableListOf<String>()

        override fun requested(exchange: Http2Exchange) {
            order += "requested ${exchange.stream}"
        }

        override fun answered(exchange: Http2Exchange) {
            order += "answered ${exchange.stream}"
        }

        override fun finished(exchange: Http2Exchange) {
            order += "finished ${exchange.stream}"
            finished += exchange
        }

        override fun stopped(why: String) {
            stopped += why
        }
    }

    private companion object {
        val GET = ":method" to "GET"
        val POST = ":method" to "POST"
        val PATH_A = ":path" to "/a"
        val PATH_B = ":path" to "/b"
        val AUTHORITY = ":authority" to "example.com"
        val OK = ":status" to "200"
    }
}
