package com.droidective.core.net.proxy

import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyRulesTest {
    @Test
    fun everythingIsOpenedWhenNothingIsNamed() {
        assertTrue(ProxyRules().opens("example.com"))
    }

    @Test
    fun namingHostsToLeaveAloneLeavesOnlyThoseAlone() {
        val rules = ProxyRules(except = listOf("pinned.example.com"))
        assertFalse(rules.opens("pinned.example.com"))
        assertTrue(rules.opens("api.example.com"))
    }

    @Test
    fun namingHostsToOpenOpensOnlyThose() {
        val rules = ProxyRules(only = listOf("api.example.com"))
        assertTrue(rules.opens("api.example.com"))
        assertFalse(rules.opens("maps.googleapis.com"))
    }

    @Test
    fun leavingAloneBeatsOpening() {
        val rules = ProxyRules(only = listOf("*.example.com"), except = listOf("pay.example.com"))
        assertTrue(rules.opens("api.example.com"))
        assertFalse(rules.opens("pay.example.com"))
    }

    @Test
    fun namingAppsReadsOnlyThose() {
        val rules = ProxyRules(apps = setOf(10144))
        assertNull(rules.passing("api.example.com", 10144))
        assertEquals(Passing.OtherApp, rules.passing("api.example.com", 10200))
        // An app nobody could tell is not one somebody chose.
        assertEquals(Passing.OtherApp, rules.passing("api.example.com", null))
    }

    @Test
    fun withNoAppsNamedEveryAppIsRead() {
        assertTrue(ProxyRules().opens("api.example.com", 10200))
        assertTrue(ProxyRules().opens("api.example.com", null))
    }

    @Test
    fun aHostTheRulesLeaveAloneSaysSo() {
        assertEquals(Passing.Rule, ProxyRules(except = listOf("a.example")).passing("a.example", 1))
        assertEquals(Passing.Rule, ProxyRules(only = listOf("a.example")).passing("b.example", 1))
        // Another app is the first thing said: it is the bigger reason.
        val both = ProxyRules(except = listOf("a.example"), apps = setOf(1))
        assertEquals(Passing.OtherApp, both.passing("a.example", 2))
    }

    @Test
    fun aWildcardIsForSubdomainsAndNotTheDomainItself() {
        assertTrue(ProxyRules.matches("*.example.com", "api.example.com"))
        assertTrue(ProxyRules.matches("*.example.com", "deep.api.example.com"))
        assertFalse(ProxyRules.matches("*.example.com", "example.com"))
        assertFalse(ProxyRules.matches("*.example.com", "notexample.com"))
    }

    @Test
    fun aHostIsMatchedWhateverCaseOrTrailingDotItArrivesWith() {
        assertTrue(ProxyRules.matches("Example.COM", "example.com."))
        assertTrue(ProxyRules.matches("*", "anything.at.all"))
        assertFalse(ProxyRules.matches("", "example.com"))
    }
}

class OriginTest {
    private fun head(target: String, vararg headers: Header) =
        RequestHead("GET", target, "HTTP/1.1", Headers(headers.toList()))

    @Test
    fun aRequestToAProxyNamesWhereItIsGoing() {
        assertEquals(
            Origin("example.com", 80),
            Origin.of(head("http://example.com/thing"), secure = false),
        )
        assertEquals(
            Origin("example.com", 8080),
            Origin.of(head("http://example.com:8080/thing"), secure = false),
        )
        // The scheme decides the port, not the connection it arrived on.
        assertEquals(
            Origin("example.com", 443),
            Origin.of(head("https://example.com/thing"), secure = false),
        )
    }

    @Test
    fun aRequestInsideATunnelLeavesItToTheHostHeader() {
        assertEquals(
            Origin("example.com", 443),
            Origin.of(head("/thing", Header("Host", "example.com")), secure = true),
        )
        assertEquals(
            Origin("example.com", 8443),
            Origin.of(head("/thing", Header("Host", "example.com:8443")), secure = true),
        )
    }

    @Test
    fun aRequestThatSaysNeitherCannotBeRouted() {
        assertNull(Origin.of(head("/thing"), secure = false))
    }

    @Test
    fun readsAConnectTarget() {
        assertEquals(Origin("example.com", 443), Origin.authority("example.com:443"))
        assertEquals(Origin("example.com", 443), Origin.authority("example.com"))
        assertEquals(Origin("::1", 8443), Origin.authority("[::1]:8443"))
        assertEquals(Origin("::1", 443), Origin.authority("[::1]"))
    }

    @Test
    fun refusesAConnectTargetThatIsNotOne() {
        assertNull(Origin.authority(""))
        assertNull(Origin.authority("example.com:port"))
        assertNull(Origin.authority("example.com:0"))
        assertNull(Origin.authority("example.com:99999"))
        assertNull(Origin.authority("two words:443"))
        assertNull(Origin.authority(":443"))
    }
}
