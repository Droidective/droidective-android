package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlowLabelsTest {
    @Test
    fun theOutcomeFollowsTheStatusClass() {
        assertEquals(FlowOutcome.Informational, FlowLabels.outcome(flow(status = 101)))
        assertEquals(FlowOutcome.Success, FlowLabels.outcome(flow(status = 204)))
        assertEquals(FlowOutcome.Redirect, FlowLabels.outcome(flow(status = 302)))
        assertEquals(FlowOutcome.ClientError, FlowLabels.outcome(flow(status = 404)))
        assertEquals(FlowOutcome.ServerError, FlowLabels.outcome(flow(status = 503)))
        assertEquals(FlowOutcome.Pending, FlowLabels.outcome(flow(status = null)))
    }

    @Test
    fun aFailureOutranksWhateverElseItCarries() {
        val refused = flow(status = null, failure = "the app refused the certificate")
        assertEquals(FlowOutcome.Failed, FlowLabels.outcome(refused))
        assertEquals("Failed", FlowLabels.status(refused))
    }

    @Test
    fun aHostPassedThroughHasNoStatusOrPath() {
        val passed = flow(status = null, tunnelled = true)
        assertEquals(FlowOutcome.PassedThrough, FlowLabels.outcome(passed))
        assertEquals("Passed", FlowLabels.status(passed))
        assertEquals("", FlowLabels.path(passed))
        // A WebSocket is tunnelled too, after a 101 that is worth showing.
        assertEquals("101", FlowLabels.status(flow(status = 101, tunnelled = true)))
    }

    @Test
    fun aRefusedConnectionNamesTheHostOnce() {
        // The app turned the certificate down before asking anything: the CONNECT's target is
        // "host:port", which read as a path made "www.google.comwww.google.com:443".
        val refused =
            flow(
                method = "CONNECT",
                host = "www.google.com",
                path = "www.google.com:443",
                status = null,
                failure = "the app refused the certificate",
            )
        assertEquals("", FlowLabels.path(refused))
        assertEquals("https://www.google.com", refused.url)
    }

    @Test
    fun statusesAreTheirNumbersAndAWaitIsAnEllipsis() {
        assertEquals("200", FlowLabels.status(flow(status = 200)))
        assertEquals("…", FlowLabels.status(flow(status = null)))
        assertEquals("/thing?q=1", FlowLabels.path(flow(path = "/thing?q=1")))
    }

    @Test
    fun sizesKeepTwoFiguresAtMost() {
        assertEquals("0 B", FlowLabels.size(0))
        assertEquals("980 B", FlowLabels.size(980))
        assertEquals("1.2 KB", FlowLabels.size(1_234))
        assertEquals("14 MB", FlowLabels.size(14_200_000))
        assertEquals("2.1 GB", FlowLabels.size(2_100_000_000))
        assertEquals("0 B", FlowLabels.size(-5))
    }

    @Test
    fun durationsUseTheScaleSomebodyThinksIn() {
        assertEquals("0 ms", FlowLabels.duration(0))
        assertEquals("38 ms", FlowLabels.duration(38))
        assertEquals("1.2 s", FlowLabels.duration(1_240))
        assertEquals("12 s", FlowLabels.duration(12_400))
        assertEquals("3 min", FlowLabels.duration(190_000))
    }

    @Test
    fun aGraphQlRequestIsNamedByItsOperationName() {
        val body = """{"operationName":"GetMenu","variables":{},"query":"query X { a }"}"""
        assertEquals("GetMenu", FlowLabels.graphQlOperation(post(body)))
    }

    @Test
    fun withoutAnOperationNameTheQueryNamesIt() {
        assertEquals(
            "PlaceOrder",
            FlowLabels.graphQlOperation(
                post("""{"query":"mutation PlaceOrder(${'$'}id: ID) {}"}""")
            ),
        )
        assertEquals(
            "Feed",
            FlowLabels.graphQlOperation(post("""{"query":"\n  subscription Feed { items }"}""")),
        )
    }

    @Test
    fun aCompressedBodyIsReadAfterDecoding() {
        val zipped = ByteArrayOutputStream()
        GZIPOutputStream(zipped).use { it.write("""{"operationName":"Zipped"}""".toByteArray()) }
        val flow =
            post("", Headers().add("Content-Encoding", "gzip"))
                .copy(requestBody = CapturedBody(zipped.toByteArray(), 40, false))
        assertEquals("Zipped", FlowLabels.graphQlOperation(flow))
    }

    @Test
    fun aPersistedQuerySentAsAGetIsNamedFromItsUrl() {
        val get = flow(method = "GET", path = "/graphql?operationName=Get%20Menu&extensions=%7B%7D")
        assertEquals("Get Menu", FlowLabels.graphQlOperation(get))
    }

    @Test
    fun anythingElseHasNoOperation() {
        listOf(
                post(""),
                post("""{"id": 4}"""),
                post("""{"operationName": ""}"""),
                post("""{"operationName": 7}"""),
                post("""{"query": "{ anonymous }"}"""),
                post("[1, 2]"),
                post("not json at all"),
                post("""{"truncated":"""),
                flow(method = "GET", path = "/menu?id=3"),
            )
            .forEach { assertNull(FlowLabels.graphQlOperation(it), it.requestBody.toString()) }
    }

    @Test
    fun aTruncatedBodyIsNotParsed() {
        val clipped =
            post("")
                .copy(
                    requestBody =
                        CapturedBody("""{"operationName":"X"}""".toByteArray(), 9_000, true)
                )
        assertNull(FlowLabels.graphQlOperation(clipped))
    }

    private fun post(body: String, headers: Headers = Headers()): Flow {
        val bytes = body.toByteArray()
        return flow(method = "POST", path = "/graphql")
            .copy(
                request = RequestHead("POST", "/graphql", "HTTP/1.1", headers),
                requestBody = CapturedBody(bytes, bytes.size.toLong(), false),
            )
    }
}
