package com.droidective.core.net.proxy

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrafficRulesTest {
    private val installed = mapOf("com.example.shop" to 10144, "com.example.maps" to 10200)

    @Test
    fun chosenAppsBecomeTheirUids() {
        val rules = TrafficRules(apps = listOf("com.example.shop")).toProxyRules(installed::get)
        assertEquals(setOf(10144), rules.apps)
    }

    @Test
    fun noAppsChosenReadsEveryApp() {
        assertEquals(emptySet(), TrafficRules().toProxyRules(installed::get).apps)
    }

    @Test
    fun appsChosenButAllGoneReadNothingRatherThanEverything() {
        // The one app chosen was uninstalled. An empty set would read every app on the phone.
        val rules = TrafficRules(apps = listOf("com.gone")).toProxyRules(installed::get)
        assertEquals(setOf(TrafficRules.NOBODY), rules.apps)
        assertEquals(Passing.OtherApp, rules.passing("api.example.com", 10144))
    }

    @Test
    fun anAppStillInstalledIsReadWhenAnotherHasGone() {
        val rules =
            TrafficRules(apps = listOf("com.gone", "com.example.maps")).toProxyRules(installed::get)
        assertEquals(setOf(10200), rules.apps)
    }

    @Test
    fun hostsAndTheSwitchCarryAcross() {
        val rules =
            TrafficRules(
                    passThrough = listOf("pinned.example.com"),
                    readOnly = listOf("*.example.com"),
                    stepAside = false,
                )
                .toProxyRules(installed::get)
        assertEquals(listOf("pinned.example.com"), rules.except)
        assertEquals(listOf("*.example.com"), rules.only)
        assertEquals(false, rules.stepAside)
    }

    @Test
    fun addingTwiceKeepsOneAndRemovingTakesItOut() {
        val rules =
            TrafficRules()
                .withApp("com.example.shop")
                .withApp("com.example.shop")
                .passingThrough("a.example")
                .passingThrough("a.example")
        assertEquals(listOf("com.example.shop"), rules.apps)
        assertEquals(listOf("a.example"), rules.passThrough)
        assertEquals(
            TrafficRules(),
            rules.withoutApp("com.example.shop").notPassingThrough("a.example"),
        )
        assertEquals(listOf("b.example"), TrafficRules().readingOnly("b.example").readOnly)
        assertEquals(
            TrafficRules(),
            TrafficRules().readingOnly("b.example").notReadingOnly("b.example"),
        )
    }

    @Test
    fun aPastedUrlBecomesItsHost() {
        assertEquals(
            "api.example.com",
            TrafficRules.pattern("https://api.example.com/v1/users?x=1"),
        )
        assertEquals("api.example.com", TrafficRules.pattern("  API.Example.com:8443  "))
        assertEquals("api.example.com", TrafficRules.pattern("http://user@api.example.com/"))
        assertEquals("example.com", TrafficRules.pattern("example.com."))
    }

    @Test
    fun wildcardsAreKept() {
        assertEquals("*.googleapis.com", TrafficRules.pattern("*.googleapis.com"))
        assertEquals("*", TrafficRules.pattern("*"))
        assertEquals("10.0.2.2", TrafficRules.pattern("10.0.2.2:8081"))
    }

    @Test
    fun whatCannotBeAHostIsRefused() {
        listOf(
                "",
                " ",
                "*.",
                "a b.com",
                "-bad.com",
                "bad-.com",
                "a..b",
                "*.*.com",
                "é;rm",
                "münchen.de",
            )
            .forEach { assertNull(TrafficRules.pattern(it), "\"$it\"") }
    }

    @Test
    fun theRulesAreKeptAndReadBack() {
        val repository = TrafficRulesRepository(createTempDirectory("rules").toFile())
        assertEquals(TrafficRules(), repository.load())
        val rules = TrafficRules(apps = listOf("com.example.shop"), stepAside = false)
        repository.save(rules)
        assertEquals(rules, repository.load())
    }
}
