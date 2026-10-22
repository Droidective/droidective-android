package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestDraftTest {
    private fun ready(draft: RequestDraft) =
        assertIs<RequestDraft.Built.Ready>(draft.build()).outgoing

    private fun invalid(draft: RequestDraft) =
        assertIs<RequestDraft.Built.Invalid>(draft.build()).reason

    private val draft =
        RequestDraft(
            method = "post",
            url = "https://api.example.com/orders?store=7",
            headers = "Content-Type: application/json\nAuthorization: Bearer abc",
            body = """{"qty":2}""",
        )

    @Test
    fun aDraftBecomesTheRequestTheServerSees() {
        val out = ready(draft)
        assertTrue(out.secure)
        assertEquals(Origin("api.example.com", 443), out.origin)
        assertEquals("POST", out.head.method)
        assertEquals("/orders?store=7", out.head.target)
        assertEquals("api.example.com", out.head.headers["Host"])
        assertEquals("application/json", out.head.headers["Content-Type"])
        assertEquals("Bearer abc", out.head.headers["Authorization"])
        assertEquals("9", out.head.headers["Content-Length"])
        assertContentEquals("""{"qty":2}""".toByteArray(), out.body)
    }

    @Test
    fun theLengthIsWorkedOutRatherThanTyped() {
        val out = ready(draft.copy(headers = "Content-Length: 999\nHost: elsewhere.example"))
        assertEquals("9", out.head.headers["Content-Length"])
        assertEquals(listOf("api.example.com"), out.head.headers.values("Host"))
    }

    @Test
    fun aPortThatIsNotTheSchemesIsKeptInTheHost() {
        val out = ready(draft.copy(url = "http://10.0.2.2:8081/status", body = ""))
        assertEquals(Origin("10.0.2.2", 8081), out.origin)
        assertEquals("10.0.2.2:8081", out.head.headers["Host"])
        assertNull(out.head.headers["Content-Length"])
    }

    @Test
    fun noHeaderValueCarriesALineBreak() {
        // A break inside a value would be a second header on the wire, one nobody saw typed. A
        // pasted carriage return splits the line instead, into headers that are both on screen.
        val split = ready(draft.copy(headers = "X-A: one\rX-B: two")).head.headers
        assertEquals("one", split["X-A"])
        assertEquals("two", split["X-B"])
        split.forEach { assertFalse('\r' in it.value || '\n' in it.value, it.toString()) }
    }

    @Test
    fun whatIsNotAHeaderSaysWhichLine() {
        assertTrue("Line 2" in invalid(draft.copy(headers = "X-A: one\nnot a header")))
        assertTrue("Line 1" in invalid(draft.copy(headers = "Bad Name: x")))
        assertTrue("Line 1" in invalid(draft.copy(headers = ": no name")))
    }

    @Test
    fun blankHeaderLinesAreIgnored() {
        assertEquals("1", ready(draft.copy(headers = "\nX-A: 1\n\n")).head.headers["X-A"])
    }

    @Test
    fun onlyAnHttpUrlWithAHostWillDo() {
        listOf("ftp://example.com/x", "example.com/x", "https:///nohost", "not a url").forEach {
            invalid(draft.copy(url = it))
        }
    }

    @Test
    fun aMethodIsAWord() {
        invalid(draft.copy(method = "GET /"))
        invalid(draft.copy(method = ""))
        assertEquals("PATCH", ready(draft.copy(method = " patch ")).head.method)
    }

    @Test
    fun aFlowOpensAsADraftOfItsRequest() {
        val flow =
            Flow(
                id = 1,
                startedAt = 0,
                secure = true,
                host = "api.example.com",
                port = 443,
                request =
                    RequestHead(
                        "PUT",
                        "/cart/7",
                        "HTTP/1.1",
                        Headers()
                            .add("Host", "api.example.com")
                            .add("Content-Type", "application/json")
                            .add("Content-Length", "11")
                            .add("Proxy-Connection", "keep-alive"),
                    ),
                requestBody = CapturedBody("""{"qty":3}""".toByteArray(), 9, false),
            )
        val draft = RequestDraft.of(flow)!!
        assertEquals("PUT", draft.method)
        assertEquals("https://api.example.com/cart/7", draft.url)
        assertEquals("Content-Type: application/json", draft.headers)
        assertEquals("""{"qty":3}""", draft.body)
        assertFalse("Proxy-Connection" in draft.headers)
    }

    @Test
    fun aDraftAsksForWhatItCanReadAndSaysSoOnScreen() {
        val flow =
            Flow(
                1,
                0,
                true,
                "a.example",
                443,
                RequestHead(
                    "GET",
                    "/",
                    "HTTP/1.1",
                    Headers().add("Accept-Encoding", "gzip, deflate, br, zstd"),
                ),
            )
        assertEquals("Accept-Encoding: gzip, deflate", RequestDraft.of(flow)!!.headers)
    }

    @Test
    fun aFlowWithNothingToEditHasNoDraft() {
        val base =
            Flow(1, 0, true, "a.example", 443, RequestHead("GET", "/", "HTTP/1.1", Headers()))
        assertNull(RequestDraft.of(base.copy(tunnelled = true)))
        assertNull(
            RequestDraft.of(base.copy(requestBody = CapturedBody(ByteArray(4), 9_000, true)))
        )
        val binary = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00)
        assertNull(RequestDraft.of(base.copy(requestBody = CapturedBody(binary, 3, false))))
    }
}
