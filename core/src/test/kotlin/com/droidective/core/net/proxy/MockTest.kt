package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockTest {
    private val menu = Mock("m1", "https://api.example.com/menu", body = """{"items":[]}""")

    @Test
    fun aMockAnswersItsUrlWhateverTheQuery() {
        assertTrue(menu.matches("GET", "https://api.example.com/menu"))
        assertTrue(menu.matches("POST", "https://api.example.com/menu?store=7"))
        assertFalse(menu.matches("GET", "https://api.example.com/menu/7"))
        assertFalse(menu.matches("GET", "http://api.example.com/menu"))
    }

    @Test
    fun theHostFoldsCaseAndThePathDoesNot() {
        assertTrue(menu.matches("GET", "https://API.Example.com/menu"))
        assertFalse(menu.matches("GET", "https://api.example.com/Menu"))
    }

    @Test
    fun aStarStandsForAnything() {
        val orders = menu.copy(url = "https://api.example.com/orders/*")
        assertTrue(orders.matches("GET", "https://api.example.com/orders/42"))
        assertTrue(orders.matches("GET", "https://api.example.com/orders/42/items"))
        assertFalse(orders.matches("GET", "https://api.example.com/orders"))
        // The rest is taken literally: a dot is a dot.
        assertFalse(
            menu
                .copy(url = "https://api.example.com/a.b")
                .matches("GET", "https://api.example.com/axb")
        )
    }

    @Test
    fun aMethodNamedIsTheOnlyOneAnswered() {
        val posts = menu.copy(method = "POST")
        assertTrue(posts.matches("post", "https://api.example.com/menu"))
        assertFalse(posts.matches("GET", "https://api.example.com/menu"))
    }

    @Test
    fun aMockTurnedOffAnswersNothing() {
        assertFalse(menu.copy(enabled = false).matches("GET", "https://api.example.com/menu"))
        assertNull(
            Mock.find(listOf(menu.copy(enabled = false)), "GET", "https://api.example.com/menu")
        )
    }

    @Test
    fun theFirstMadeAnswersFirst() {
        val wide = menu.copy(id = "wide", url = "https://api.example.com/*")
        assertEquals("m1", Mock.find(listOf(menu, wide), "GET", "https://api.example.com/menu")?.id)
        assertEquals(
            "wide",
            Mock.find(listOf(wide, menu), "GET", "https://api.example.com/menu")?.id,
        )
    }

    @Test
    fun theAnswerStatesItsOwnLength() {
        val (head, body) =
            menu
                .copy(
                    status = 503,
                    headers =
                        listOf(
                            MockHeader("Content-Type", "application/json"),
                            MockHeader("Content-Length", "9999"),
                            MockHeader("Content-Encoding", "gzip"),
                            MockHeader("Transfer-Encoding", "chunked"),
                        ),
                )
                .response()
        assertEquals(503, head.status)
        assertEquals("Service Unavailable", head.reason)
        assertEquals("application/json", head.headers["Content-Type"])
        assertEquals(listOf("12"), head.headers.values("Content-Length"))
        assertNull(head.headers["Content-Encoding"])
        assertNull(head.headers["Transfer-Encoding"])
        assertContentEquals("""{"items":[]}""".toByteArray(), body)
    }

    @Test
    fun aMockStartsFromWhatTheServerAnswered() {
        val zipped = ByteArrayOutputStream()
        GZIPOutputStream(zipped).use { it.write("""{"open":true}""".toByteArray()) }
        val flow =
            Flow(
                id = 3,
                startedAt = 0,
                secure = true,
                host = "api.example.com",
                port = 443,
                request = RequestHead("get", "/status?x=1", "HTTP/1.1", Headers()),
                response =
                    ResponseHead(
                        "HTTP/1.1",
                        200,
                        "OK",
                        Headers()
                            .add("Content-Type", "application/json")
                            .add("Content-Encoding", "gzip")
                            .add("Content-Length", "33"),
                    ),
                responseBody = CapturedBody(zipped.toByteArray(), 33, false),
            )
        val mock = assertNotNull(Mock.of(flow, "new"))
        assertEquals("https://api.example.com/status", mock.url)
        assertEquals("GET", mock.method)
        assertEquals(200, mock.status)
        assertEquals("""{"open":true}""", mock.body)
        assertEquals(listOf(MockHeader("Content-Type", "application/json")), mock.headers)
    }

    @Test
    fun noWholeTextAnswerNoMock() {
        val base =
            Flow(1, 0, true, "a.example", 443, RequestHead("GET", "/", "HTTP/1.1", Headers()))
        assertNull(Mock.of(base, "x"))
        val answered = base.copy(response = ResponseHead("HTTP/1.1", 200, "OK", Headers()))
        assertNull(
            Mock.of(answered.copy(responseBody = CapturedBody(ByteArray(2), 9_000, true)), "x")
        )
        val binary = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertNull(Mock.of(answered.copy(responseBody = CapturedBody(binary, 2, false)), "x"))
    }

    @Test
    fun onlyAnHttpUrlWithAHostIsAPattern() {
        assertTrue(Mock.isPattern("https://api.example.com/menu"))
        assertTrue(Mock.isPattern("http://10.0.2.2:8081/*"))
        assertFalse(Mock.isPattern("api.example.com/menu"))
        assertFalse(Mock.isPattern("https:///menu"))
        assertFalse(Mock.isPattern("https://a b.com/"))
    }

    @Test
    fun aDraftBecomesTheMockItDescribes() {
        val draft =
            MockDraft(
                url = " https://api.example.com/menu ",
                method = "post",
                status = "201",
                headers = "Content-Type: application/json\n\nX-Mocked: yes",
                body = "{}",
                enabled = true,
            )
        val mock = (draft.build("m9") as MockDraft.Built.Ready).mock
        assertEquals("https://api.example.com/menu", mock.url)
        assertEquals("POST", mock.method)
        assertEquals(201, mock.status)
        assertEquals(
            listOf(MockHeader("Content-Type", "application/json"), MockHeader("X-Mocked", "yes")),
            mock.headers,
        )
        // Back as a draft, it reads as the mock kept it: tidied, and blank lines gone.
        assertEquals(
            draft.copy(
                url = mock.url,
                method = "POST",
                headers = "Content-Type: application/json\nX-Mocked: yes",
            ),
            MockDraft.of(mock),
        )
    }

    @Test
    fun aFileNameStaysInTheMocksFolder() {
        listOf("menu.json", "shop/menu.json", "a b.png", ".hidden").forEach {
            assertTrue(Mock.isFileName(it), it)
        }
        listOf("", "/sdcard/menu.json", "../secret", "shop/../../x", "shop//menu", "a\\b", "./menu")
            .forEach { assertFalse(Mock.isFileName(it), it) }
    }

    @Test
    fun anAnswerFromAFileIsTypedByItsExtensionUnlessTheMockSays() {
        val fromFile = menu.copy(file = "menu.JSON")
        val (head, bytes) = fromFile.response("[1]".toByteArray())

        assertEquals("application/json", head.headers[Headers.CONTENT_TYPE])
        assertEquals("3", head.headers[Headers.CONTENT_LENGTH])
        assertEquals("[1]", bytes.decodeToString())
        val said = fromFile.copy(headers = listOf(MockHeader("Content-Type", "text/plain")))
        assertEquals("text/plain", said.response(ByteArray(0)).first.headers[Headers.CONTENT_TYPE])
        assertNull(
            menu.copy(file = "data.bin").response(ByteArray(0)).first.headers[Headers.CONTENT_TYPE]
        )
    }

    @Test
    fun aDraftNamesItsFile() {
        val draft = MockDraft.of(menu).copy(file = " fixtures/menu.json ")
        val mock = (draft.build("m1") as MockDraft.Built.Ready).mock
        assertEquals("fixtures/menu.json", mock.file)
        assertEquals("fixtures/menu.json", MockDraft.of(mock).file)
        assertNull((MockDraft.of(menu).build("m1") as MockDraft.Built.Ready).mock.file)
        assertTrue(draft.copy(file = "../menu.json").build("m1") is MockDraft.Built.Invalid)
    }

    @Test
    fun aDraftThatDoesNotDescribeAMockSaysWhy() {
        val good = MockDraft.of(menu)
        listOf(
                good.copy(url = "api.example.com/menu"),
                good.copy(status = "OK"),
                good.copy(status = "700"),
                good.copy(method = "GET /"),
                good.copy(headers = "no colon here"),
                good.copy(headers = ": no name"),
            )
            .forEach { assertTrue(it.build("x") is MockDraft.Built.Invalid, it.toString()) }
        // A blank method answers any.
        assertNull((good.copy(method = " ").build("x") as MockDraft.Built.Ready).mock.method)
    }

    @Test
    fun anAnswerThatCantBeMockedSaysWhatToDoInstead() {
        val base =
            Flow(1, 0, true, "a.example", 443, RequestHead("GET", "/", "HTTP/1.1", Headers()))
        assertTrue("Nothing" in Mock.whyNot(base)!!)
        val zstd =
            base.copy(
                response =
                    ResponseHead("HTTP/1.1", 200, "OK", Headers().add("Content-Encoding", "zstd")),
                responseBody = CapturedBody(byteArrayOf(0x28, 0xb5.toByte()), 2, false),
            )
        assertTrue("Send again" in Mock.whyNot(zstd)!!)
        val plain =
            base.copy(
                response = ResponseHead("HTTP/1.1", 200, "OK", Headers()),
                responseBody = CapturedBody("ok".toByteArray(), 2, false),
            )
        assertNull(Mock.whyNot(plain))
    }
}
