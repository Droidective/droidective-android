package com.droidective.core.net.http

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadersTest {
    private val headers =
        Headers(
            listOf(
                Header("Host", "example.com"),
                Header("content-length", "12"),
                Header("Set-Cookie", "a=1"),
                Header("Set-Cookie", "b=2"),
            )
        )

    @Test
    fun aNameIsFoundWhateverCaseItWasSentIn() {
        assertEquals("example.com", headers["host"])
        assertEquals("12", headers["Content-Length"])
        assertTrue(headers.has("HOST"))
        assertNull(headers["Accept"])
    }

    @Test
    fun aRepeatedNameKeepsEveryValue() {
        assertEquals(listOf("a=1", "b=2"), headers.values("set-cookie"))
        // Asking for one of a repeated name gives the first, the way a parser would read it.
        assertEquals("a=1", headers["Set-Cookie"])
    }

    @Test
    fun droppingNamesIgnoresCaseAndKeepsOrder() {
        val rest = headers.without("CONTENT-LENGTH", "set-cookie")
        assertEquals(listOf("Host"), rest.all.map { it.name })
    }

    @Test
    fun aStatedLengthHasToBeANumberThatCouldBeOne() {
        assertEquals(12L, headers.contentLength)
        assertNull(Headers(listOf(Header("Content-Length", "eleven"))).contentLength)
        assertNull(Headers(listOf(Header("Content-Length", "-1"))).contentLength)
        assertNull(Headers().contentLength)
    }

    @Test
    fun onlyTheLastCodingDecidesWhetherTheBodyIsChunked() {
        assertTrue(Headers(listOf(Header("Transfer-Encoding", "chunked"))).chunked)
        assertTrue(Headers(listOf(Header("transfer-encoding", "gzip, chunked"))).chunked)
        // Chunked has to be last; anything after it means the sender framed it some other way.
        assertFalse(Headers(listOf(Header("Transfer-Encoding", "chunked, gzip"))).chunked)
        assertFalse(Headers().chunked)
    }

    @Test
    fun identityIsNotACompression() {
        assertEquals("gzip", Headers(listOf(Header("Content-Encoding", "GZIP"))).contentEncoding)
        assertNull(Headers(listOf(Header("Content-Encoding", "identity"))).contentEncoding)
    }
}

class RequestLineTest {
    @Test
    fun readsTheThreeParts() {
        assertEquals(
            RequestLine("GET", "/thing?x=1", "HTTP/1.1"),
            RequestLine.parse("GET /thing?x=1 HTTP/1.1"),
        )
        assertEquals(
            RequestLine("CONNECT", "example.com:443", "HTTP/1.1"),
            RequestLine.parse("CONNECT example.com:443 HTTP/1.1"),
        )
    }

    @Test
    fun refusesWhatIsNotARequestLine() {
        assertNull(RequestLine.parse(""))
        assertNull(RequestLine.parse("GET /thing"))
        assertNull(RequestLine.parse("GET /thing HTTP/1.1 extra"))
        assertNull(RequestLine.parse("GET /thing SPDY/3"))
        // A method is a token: a separator in it means this is not a request at all.
        assertNull(RequestLine.parse("GE(T /thing HTTP/1.1"))
    }
}

class StatusLineTest {
    @Test
    fun readsAStatusWithOrWithoutAReason() {
        assertEquals(StatusLine("HTTP/1.1", 200, "OK"), StatusLine.parse("HTTP/1.1 200 OK"))
        assertEquals(StatusLine("HTTP/1.1", 204, ""), StatusLine.parse("HTTP/1.1 204"))
        assertEquals(
            StatusLine("HTTP/1.1", 404, "Not Found Here"),
            StatusLine.parse("HTTP/1.1 404 Not Found Here"),
        )
    }

    @Test
    fun refusesAStatusThatCouldNotBeOne() {
        assertNull(StatusLine.parse("HTTP/1.1 twenty OK"))
        assertNull(StatusLine.parse("HTTP/1.1 99 Too Low"))
        assertNull(StatusLine.parse("HTTP/1.1 600 Too High"))
        assertNull(StatusLine.parse("200 OK"))
    }
}

class RequestHeadTest {
    private fun head(target: String) = RequestHead("GET", target, "HTTP/1.1", Headers())

    @Test
    fun anAbsoluteTargetBecomesThePathAServerWillAnswer() {
        assertEquals("/thing?x=1", head("http://example.com/thing?x=1").path)
        assertEquals("/", head("http://example.com").path)
        assertTrue(head("http://example.com/thing").absolute)
    }

    @Test
    fun anOriginTargetIsAlreadyWhatTheServerWants() {
        assertEquals("/thing?x=1", head("/thing?x=1").path)
        assertFalse(head("/thing").absolute)
    }

    @Test
    fun writesTheHeadBackOutAsItWouldGoOnTheWire() {
        val written =
            RequestHead(
                    "POST",
                    "/send",
                    "HTTP/1.1",
                    Headers(listOf(Header("Host", "example.com"), Header("Content-Length", "2"))),
                )
                .bytes()
        assertEquals(
            "POST /send HTTP/1.1\r\nHost: example.com\r\nContent-Length: 2\r\n\r\n",
            String(written, Charsets.ISO_8859_1),
        )
    }

    @Test
    fun aResponseWithNoReasonDoesNotLeaveATrailingSpace() {
        val written = ResponseHead("HTTP/1.1", 204, "", Headers()).bytes()
        assertEquals("HTTP/1.1 204\r\n\r\n", String(written, Charsets.ISO_8859_1))
    }
}

class BodyFramingTest {
    private fun request(vararg headers: Header) =
        RequestHead("POST", "/", "HTTP/1.1", Headers(headers.toList()))

    private fun response(status: Int, vararg headers: Header) =
        ResponseHead("HTTP/1.1", status, "", Headers(headers.toList()))

    @Test
    fun aRequestWithoutAStatedLengthHasNoBody() {
        assertEquals(BodyFraming.Empty, BodyFraming.of(request()))
        assertEquals(BodyFraming.Fixed(5), BodyFraming.of(request(Header("Content-Length", "5"))))
        assertEquals(
            BodyFraming.Chunked,
            BodyFraming.of(request(Header("Transfer-Encoding", "chunked"))),
        )
    }

    @Test
    fun chunkedWinsOverAStatedLength() {
        val head = request(Header("Transfer-Encoding", "chunked"), Header("Content-Length", "5"))
        assertEquals(BodyFraming.Chunked, BodyFraming.of(head))
    }

    @Test
    fun theStatusesThatCarryNoBodyCarryNoneEvenWhenTheySayALength() {
        assertEquals(
            BodyFraming.Empty,
            BodyFraming.of(response(204, Header("Content-Length", "5")), "GET"),
        )
        assertEquals(
            BodyFraming.Empty,
            BodyFraming.of(response(304, Header("Content-Length", "5")), "GET"),
        )
        assertEquals(BodyFraming.Empty, BodyFraming.of(response(100), "GET"))
    }

    @Test
    fun aHeadIsAnsweredWithALengthAndNoBody() {
        val head = response(200, Header("Content-Length", "1024"))
        assertEquals(BodyFraming.Empty, BodyFraming.of(head, "HEAD"))
        assertEquals(BodyFraming.Fixed(1024), BodyFraming.of(head, "GET"))
    }

    @Test
    fun anAcceptedConnectIsATunnelRatherThanAMessage() {
        assertEquals(BodyFraming.Empty, BodyFraming.of(response(200), "CONNECT"))
        // A refused one is an ordinary response and may explain itself in a body.
        assertEquals(BodyFraming.UntilClose, BodyFraming.of(response(403), "CONNECT"))
    }

    @Test
    fun aResponseThatStatesNothingRunsUntilTheConnectionCloses() {
        assertEquals(BodyFraming.UntilClose, BodyFraming.of(response(200), "GET"))
    }
}

class HttpReaderTest {
    private fun reader(text: String, maxLine: Int = 16_384, maxHeaders: Int = 200) =
        HttpReader(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)), maxLine, maxHeaders)

    @Test
    fun readsARequestAndStopsExactlyAtItsBody() {
        val stream = ByteArrayInputStream("GET /x HTTP/1.1\r\nHost: a\r\n\r\nBODY".toByteArray())
        val head = HttpReader(stream).readRequest()
        assertEquals(
            RequestHead("GET", "/x", "HTTP/1.1", Headers(listOf(Header("Host", "a")))),
            head,
        )
        // Whatever follows the head is the body, and it must still be there to forward.
        assertContentEquals("BODY".toByteArray(), stream.readBytes())
    }

    @Test
    fun readsAResponse() {
        val head = reader("HTTP/1.1 301 Moved\r\nLocation: /there\r\n\r\n").readResponse()
        assertEquals(
            ResponseHead("HTTP/1.1", 301, "Moved", Headers(listOf(Header("Location", "/there")))),
            head,
        )
    }

    @Test
    fun aHeaderValueIsTrimmedButItsNameIsKeptAsSent() {
        val head = reader("GET / HTTP/1.1\r\nX-Odd-Case:   spaced   \r\n\r\n").readRequest()
        assertEquals(listOf(Header("X-Odd-Case", "spaced")), head?.headers?.all)
    }

    @Test
    fun aBareNewlineIsToleratedBecauseSomeServersSendOne() {
        val head = reader("HTTP/1.1 200 OK\nServer: odd\n\n").readResponse()
        assertEquals("odd", head?.headers?.get("Server"))
    }

    @Test
    fun aConnectionThatClosedBeforeAnythingArrivedIsNotAnError() {
        assertNull(reader("").readRequest())
        assertNull(reader("").readResponse())
    }

    @Test
    fun aConnectionThatStoppedPartWayThroughIs() {
        assertFailsWith<IOException> { reader("GET / HTTP/1.1").readRequest() }
        assertFailsWith<IOException> { reader("GET / HTTP/1.1\r\nHost: a\r\n").readRequest() }
    }

    @Test
    fun refusesWhatCannotBeAMessage() {
        assertFailsWith<IOException> { reader("nonsense\r\n\r\n").readRequest() }
        assertFailsWith<IOException> {
            reader("GET / HTTP/1.1\r\nno colon here\r\n\r\n").readRequest()
        }
    }

    @Test
    fun willNotBeMadeToAllocateWithoutEnd() {
        val long = "GET /" + "x".repeat(100) + " HTTP/1.1\r\n\r\n"
        assertFailsWith<IOException> { reader(long, maxLine = 32).readRequest() }
        val many = "GET / HTTP/1.1\r\n" + "X-Pad: 1\r\n".repeat(10) + "\r\n"
        assertFailsWith<IOException> { reader(many, maxHeaders = 4).readRequest() }
    }
}
