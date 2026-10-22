package com.droidective.core.net.proxy

import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemapTest {
    private val staging = Remap("r1", "https://api.example.com/*", "https://staging.example.com")

    @Test
    fun aServerIsASchemeAHostAndAPortIfNeedBe() {
        assertEquals(Server(false, Origin("10.0.2.2", 3000)), Server.of(" http://10.0.2.2:3000 "))
        assertEquals(
            Server(true, Origin("staging.example.com", 443)),
            Server.of("https://staging.example.com/"),
        )
        assertEquals(Server(true, Origin("::1", 8443)), Server.of("https://[::1]:8443"))
    }

    @Test
    fun anythingMoreThanAServerIsNotOne() {
        listOf(
                "staging.example.com",
                "ftp://staging.example.com",
                "https://",
                "https://staging.example.com/api",
                "https://staging.example.com?x=1",
                "https://staging.example.com#top",
                "https://someone@staging.example.com",
            )
            .forEach { assertNull(Server.of(it), it) }
    }

    @Test
    fun aServerIsNamedAsHostWouldNameIt() {
        assertEquals(
            "https://staging.example.com",
            Server.of("https://staging.example.com:443")?.url,
        )
        assertEquals("http://10.0.2.2:3000", Server(false, Origin("10.0.2.2", 3000)).url)
        assertEquals("[::1]:8443", Server(true, Origin("::1", 8443)).authority)
    }

    @Test
    fun aRequestSentElsewhereNamesTheServerItGoesTo() {
        val head =
            RequestHead(
                "GET",
                "/menu",
                "HTTP/1.1",
                Headers().add("Host", "api.example.com").add("Accept", "*/*"),
            )

        val hosted = Server(false, Origin("10.0.2.2", 3000)).hosting(head)

        assertEquals(
            listOf("Host" to "10.0.2.2:3000", "Accept" to "*/*"),
            hosted.headers.map { it.name to it.value },
        )
    }

    @Test
    fun theFirstRemapForAUrlSaysWhereItGoes() {
        val local = Remap("r2", "https://api.example.com/menu", "http://10.0.2.2:3000")

        assertEquals(
            "https://staging.example.com",
            Remap.route(listOf(staging, local), "https://api.example.com/menu?x=1")?.url,
        )
        assertEquals(
            "http://10.0.2.2:3000",
            Remap.route(
                    listOf(staging.copy(enabled = false), local),
                    "https://api.example.com/menu",
                )
                ?.url,
        )
        assertNull(Remap.route(listOf(staging), "https://cdn.example.com/logo.png"))
    }

    @Test
    fun aRemapWithNowhereToGoSendsNothingElsewhere() {
        val nowhere = staging.copy(to = "staging")
        assertNull(Remap.route(listOf(nowhere), "https://api.example.com/menu"))
        assertNotNull(nowhere.problem())
    }

    @Test
    fun oneThatCantBeKeptSaysWhy() {
        assertNull(staging.problem())
        listOf(
                staging.copy(from = "api.example.com/*"),
                staging.copy(to = ""),
                staging.copy(to = "https://staging.example.com/v2"),
            )
            .forEach { assertTrue(it.problem() != null, it.toString()) }
    }

    @Test
    fun oneMadeFromAFlowTakesEveryRequestToItsServer() {
        val made = Remap.of(flow(host = "api.example.com", path = "/menu?x=1"), "r3")
        assertEquals("https://api.example.com/*", made.from)
        assertTrue(made.matches("https://api.example.com/orders/42"))
    }

    @Test
    fun anOutgoingRequestIsMatchedByWhereItIsAimed() {
        val head = RequestHead("GET", "/menu?x=1", "HTTP/1.1", Headers())
        val outgoing = Outgoing(true, Origin("api.example.com", 443), head, ByteArray(0))
        assertEquals("https://api.example.com/menu?x=1", outgoing.url)
    }
}
