package com.droidective.core.net.proxy

import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnreadTest {
    private val rules = TrafficRules(passThrough = listOf("*.pinned.example", "maps.example.com"))

    @Test
    fun aCallReadOrFailedOnTheWayHasNothingToExplain() {
        assertNull(Unread.of(flow(id = 1, status = 200), rules))
        assertNull(
            Unread.of(flow(id = 2, failure = "the server answered nothing"), rules),
            "the app's call was read; the server let it down",
        )
    }

    @Test
    fun eachReasonNamesItsFix() {
        assertEquals(
            Unread.Refused(before = false),
            Unread.of(flow(id = 1, failure = "${TlsFailure.REFUSED} (certificate unknown)"), rules),
        )
        assertEquals(
            Unread.Refused(before = true),
            Unread.of(passed(2, Passing.Refused, "api.example.com"), rules),
        )
        assertEquals(
            Unread.NotChosen,
            Unread.of(passed(3, Passing.OtherApp, "api.example.com"), rules),
        )
        assertEquals(
            Unread.HostRule("*.pinned.example"),
            Unread.of(passed(4, Passing.Rule, "auth.pinned.example"), rules),
        )
        assertEquals(
            Unread.HostRule(null),
            Unread.of(passed(5, Passing.Rule, "api.example.com"), rules),
            "no pass-through names it: read-only hosts left it out",
        )
        assertEquals(
            Unread.Unreadable,
            Unread.of(passed(6, Passing.Unreadable, "h2.example"), rules),
        )
    }

    @Test
    fun theUnreadAreGroupedByAppAndReasonNewestFirst() {
        val flows =
            listOf(
                // Read before it refused: no sign it trusts the certificate now.
                flow(id = 0, app = "dev.shop", status = 200, host = "api.shop.dev"),
                flow(id = 1, app = "dev.shop", failure = TlsFailure.REFUSED, host = "api.shop.dev"),
                passed(2, Passing.OtherApp, "maps.googleapis.com", app = "com.google.android.gms"),
                passed(3, Passing.Refused, "api.shop.dev", app = "dev.shop"),
                flow(id = 4, app = "dev.shop", failure = TlsFailure.REFUSED, host = "cdn.shop.dev"),
            )
        assertEquals(
            listOf(
                Unread.ByApp(
                    "dev.shop",
                    Unread.Refused(before = false),
                    count = 3,
                    hosts = listOf("cdn.shop.dev", "api.shop.dev"),
                ),
                Unread.ByApp(
                    "com.google.android.gms",
                    Unread.NotChosen,
                    1,
                    listOf("maps.googleapis.com"),
                ),
            ),
            Unread.byApp(flows, rules.withApp("dev.shop")),
        )
        assertEquals(emptyList(), Unread.byApp(listOf(flow(id = 9, status = 200)), rules))
    }

    @Test
    fun aReasonThatNoLongerHoldsIsLeftOut() {
        val flows =
            listOf(
                passed(1, Passing.OtherApp, "api.shop.dev", app = "dev.shop"),
                passed(2, Passing.Rule, "maps.example.com", app = "dev.maps"),
                flow(id = 3, app = "dev.pay", failure = TlsFailure.REFUSED, host = "api.pay.dev"),
            )
        val choosing =
            TrafficRules(
                apps = listOf("com.android.chrome"),
                passThrough = listOf("maps.example.com"),
            )
        assertEquals(3, Unread.byApp(flows, choosing).size)
        assertEquals(
            listOf("dev.maps", "dev.pay"),
            Unread.byApp(flows, choosing.withApp("dev.shop")).mapNotNull { it.app }.sorted(),
            "the app is read now",
        )
        assertEquals(
            listOf("dev.pay", "dev.shop"),
            Unread.byApp(flows, choosing.notPassingThrough("maps.example.com"))
                .mapNotNull { it.app }
                .sorted(),
            "the rule is gone",
        )
        val trustedSince = flows + flow(id = 4, app = "dev.pay", status = 200, host = "api.pay.dev")
        assertEquals(
            listOf("dev.maps", "dev.shop"),
            Unread.byApp(trustedSince, choosing).mapNotNull { it.app }.sorted(),
            "its calls are read since it refused",
        )
    }

    @Test
    fun aRowSaysWhyInAFewWords() {
        assertEquals("doesn't trust the certificate", Unread.label(Unread.Refused(before = true)))
        assertEquals("not read: not an app being read", Unread.label(Unread.NotChosen))
        assertEquals(
            "not read: *.pinned.example is passed through",
            Unread.label(Unread.HostRule("*.pinned.example")),
        )
        assertEquals("not read: not a host being read", Unread.label(Unread.HostRule(null)))
    }

    @Test
    fun forgettingAnAppsRefusalsReadsItAgainAndNoOneElse() {
        val refusals = Refusals()
        refusals.note(10144, "api.shop.dev")
        refusals.note(10144, "cdn.shop.dev")
        refusals.note(10200, "api.shop.dev")
        refusals.forgetApp(10144)
        assertEquals(listOf(Refusals.Refusal(10200, "api.shop.dev")), refusals.all())
    }

    private fun passed(id: Long, passing: Passing, host: String, app: String? = null) =
        flow(
            id = id,
            host = host,
            method = "CONNECT",
            tunnelled = true,
            passing = passing,
            app = app,
        )

    private fun flow(
        id: Long,
        host: String = "api.example.com",
        method: String = "GET",
        status: Int? = null,
        failure: String? = null,
        tunnelled: Boolean = false,
        passing: Passing? = null,
        app: String? = null,
    ) =
        Flow(
            id = id,
            startedAt = id,
            secure = true,
            host = host,
            port = 443,
            request =
                RequestHead(
                    method,
                    if (method == "CONNECT") "$host:443" else "/",
                    "HTTP/1.1",
                    Headers(),
                ),
            response = status?.let { ResponseHead("HTTP/1.1", it, "", Headers()) },
            failure = failure,
            tunnelled = tunnelled,
            passing = passing,
            app = app,
        )
}
