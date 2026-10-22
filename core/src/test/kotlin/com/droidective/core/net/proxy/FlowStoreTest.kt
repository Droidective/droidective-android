package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds the flows the store and the filter are asked about. */
internal fun flow(
    id: Long = 1,
    method: String = "GET",
    host: String = "api.example.com",
    path: String = "/thing",
    secure: Boolean = true,
    status: Int? = 200,
    app: String? = null,
    failure: String? = null,
    tunnelled: Boolean = false,
    responseBytes: Int = 0,
): Flow =
    Flow(
        id = id,
        startedAt = 1_000,
        secure = secure,
        host = host,
        port = if (secure) 443 else 80,
        request = RequestHead(method, path, "HTTP/1.1", Headers()),
        response = status?.let { ResponseHead("HTTP/1.1", it, "", Headers()) },
        responseBody =
            status?.let { CapturedBody(ByteArray(responseBytes), responseBytes.toLong(), false) },
        finishedAt = 1_100,
        failure = failure,
        tunnelled = tunnelled,
        app = app,
    )

class FlowStoreTest {
    @Test
    fun keepsWhatItIsGivenInTheOrderItWasAsked() {
        val store = FlowStore()
        store.put(flow(id = 1, path = "/one"))
        store.put(flow(id = 2, path = "/two"))
        assertEquals(listOf(1L, 2L), store.all().map { it.id })
    }

    @Test
    fun theAnswerReplacesTheRequestWithoutMovingIt() {
        val store = FlowStore()
        store.put(flow(id = 1, status = null))
        store.put(flow(id = 2))
        // The first flow finishes last, and stays first because that is when it was asked.
        store.put(flow(id = 1, status = 500))
        assertEquals(listOf(1L, 2L), store.all().map { it.id })
        assertEquals(500, store.get(1)?.status)
        assertEquals(2, store.size())
    }

    @Test
    fun theOldestGoWhenThereAreTooMany() {
        val store = FlowStore(maxFlows = 3)
        (1L..5L).forEach { store.put(flow(id = it)) }
        assertEquals(listOf(3L, 4L, 5L), store.all().map { it.id })
    }

    @Test
    fun theOldestGoWhenTheyAddUpToTooMuch() {
        // Two will fit; the third pushes the first out, whatever the count allows.
        val store = FlowStore(maxFlows = 100, maxBytes = 30_000)
        (1L..3L).forEach { store.put(flow(id = it, responseBytes = 10_000)) }
        assertEquals(listOf(2L, 3L), store.all().map { it.id })
        assertTrue(store.heldBytes() <= 30_000)
    }

    @Test
    fun replacingAFlowCountsTheNewSizeRatherThanBoth() {
        val store = FlowStore()
        store.put(flow(id = 1, status = null))
        val afterRequest = store.heldBytes()
        store.put(flow(id = 1, responseBytes = 1_000))
        assertEquals(afterRequest + 1_000, store.heldBytes())
    }

    @Test
    fun clearingLetsTheRoomBackAgain() {
        val store = FlowStore()
        store.put(flow(id = 1, responseBytes = 5_000))
        store.clear()
        assertEquals(0, store.size())
        assertEquals(0, store.heldBytes())
        assertNull(store.get(1))
    }
}

class FlowFilterTest {
    private val flows =
        listOf(
            flow(
                id = 1,
                method = "GET",
                host = "api.example.com",
                path = "/users",
                status = 200,
                app = "com.example.shop",
            ),
            flow(
                id = 2,
                method = "POST",
                host = "api.example.com",
                path = "/orders",
                status = 422,
                app = "com.example.shop",
            ),
            flow(
                id = 3,
                method = "GET",
                host = "cdn.other.com",
                path = "/logo.png",
                status = 404,
                app = "com.other.maps",
            ),
            flow(
                id = 4,
                method = "GET",
                host = "api.example.com",
                path = "/slow",
                status = null,
                failure = "timed out",
                app = "com.example.shop",
            ),
            flow(
                id = 5,
                method = "CONNECT",
                host = "pinned.example.com",
                path = "/",
                status = null,
                tunnelled = true,
                app = "com.bank.app",
            ),
        )

    private fun ids(query: String) =
        flows.filter { FlowFilter.parse(query).matches(it) }.map { it.id }

    @Test
    fun anEmptyQueryKeepsEverything() {
        assertTrue(FlowFilter.parse("").isEmpty)
        assertEquals(flows.map { it.id }, ids(""))
        assertEquals(flows.map { it.id }, ids("   "))
    }

    @Test
    fun aWordIsLookedForInTheUrlAndTheApp() {
        assertEquals(listOf(1L), ids("users"))
        assertEquals(listOf(3L), ids("logo"))
        assertEquals(listOf(1L, 2L, 4L), ids("com.example.shop"))
    }

    @Test
    fun aPastedUrlIsSearchedForRatherThanReadAsAField() {
        assertEquals(listOf(1L), ids("https://api.example.com/users"))
    }

    @Test
    fun theNamedFieldsNarrowIt() {
        assertEquals(listOf(2L), ids("method:post"))
        assertEquals(listOf(3L), ids("host:cdn"))
        assertEquals(listOf(5L), ids("app:bank"))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids("scheme:https"))
    }

    @Test
    fun statusTakesANumberOrAWholeClassOfThem() {
        assertEquals(listOf(3L), ids("status:404"))
        assertEquals(listOf(2L, 3L), ids("status:4xx"))
        assertEquals(listOf(1L), ids("status:2XX"))
        // A flow that never got an answer has no status, so it matches none of them.
        assertFalse(FlowFilter.parse("status:2xx").matches(flows[3]))
    }

    @Test
    fun theStatesSayWhatBecameOfIt() {
        assertEquals(listOf(4L), ids("is:failed"))
        assertEquals(listOf(5L), ids("is:tunnelled"))
        assertEquals(listOf(5L), ids("is:pending"))
    }

    @Test
    fun wordsAreJoinedByAnd() {
        assertEquals(listOf(2L), ids("host:api.example.com method:post"))
        assertEquals(emptyList(), ids("method:post status:200"))
    }

    @Test
    fun aMinusInFrontMeansTheOpposite() {
        assertEquals(listOf(1L, 2L, 4L, 5L), ids("-host:cdn"))
        assertEquals(listOf(3L, 5L), ids("-app:shop"))
        // Nothing known about the app is not the same as the app being something else.
        assertEquals(listOf(1L, 3L, 4L, 5L), ids("-method:post"))
    }

    @Test
    fun aFieldWithNothingAfterItAsksNothing() {
        assertTrue(FlowFilter.parse("host:").isEmpty)
        assertEquals(listOf(2L), ids("host: method:post"))
    }
}

class FlowSummaryTest {
    @Test
    fun countsTheAppsAndHostsWithTheBusiestFirst() {
        val flows =
            listOf(
                flow(id = 1, app = "com.a", host = "one.com"),
                flow(id = 2, app = "com.b", host = "one.com"),
                flow(id = 3, app = "com.b", host = "two.com"),
                flow(id = 4, app = null, host = "two.com"),
                flow(id = 5, app = "com.b", host = "one.com"),
            )
        assertEquals(listOf("com.b" to 3, "com.a" to 1), FlowSummary.apps(flows))
        assertEquals(listOf("one.com" to 3, "two.com" to 2), FlowSummary.hosts(flows))
    }

    @Test
    fun aTieIsBrokenByNameSoTheChipsDoNotJumpAround() {
        val flows = listOf(flow(id = 1, app = "com.z"), flow(id = 2, app = "com.a"))
        assertEquals(listOf("com.a" to 1, "com.z" to 1), FlowSummary.apps(flows))
    }

    @Test
    fun passedConnectionsAreTheOnesNeverRead() {
        val passed = flow(id = 9, status = null, tunnelled = true)
        val socket = flow(id = 10, status = 101, tunnelled = true)
        val filter = FlowFilter.parse("is:passed")
        assertTrue(filter.matches(passed))
        assertFalse(filter.matches(socket))
        assertFalse(filter.matches(flow(id = 11)))
    }

    @Test
    fun repeatsAreFoundByWhatTheyAre() {
        val filter = FlowFilter.parse("is:repeat")
        assertTrue(filter.matches(flow(id = 12).copy(repeatOf = 3)))
        assertFalse(filter.matches(flow(id = 3)))
    }

    @Test
    fun aChipAddsItsWordAndTakesItBackOut() {
        val added = FlowFilter.toggled("login", "app:com.example.shop")
        assertEquals("login app:com.example.shop", added)
        assertTrue(FlowFilter.holds(added, "app:com.example.shop"))
        assertEquals("login", FlowFilter.toggled(added, "app:com.example.shop"))
        assertEquals("is:failed", FlowFilter.toggled("", "is:failed"))
    }

    @Test
    fun aChipOnlyCountsAWholeWord() {
        // Typed by hand, a longer word that starts the same is a different search.
        assertFalse(FlowFilter.holds("app:com.example.shopping", "app:com.example.shop"))
        assertEquals(
            "app:com.example.shopping app:com.example.shop",
            FlowFilter.toggled("app:com.example.shopping", "app:com.example.shop"),
        )
        assertEquals(
            "a b",
            FlowFilter.toggled("  a \t b  ", "c").let { FlowFilter.toggled(it, "c") },
        )
    }
}
