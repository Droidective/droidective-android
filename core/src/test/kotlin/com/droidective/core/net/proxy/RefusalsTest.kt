package com.droidective.core.net.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefusalsTest {
    @Test
    fun aRefusalIsOneAppsAndOneHosts() {
        val refusals = Refusals()
        refusals.note(10144, "api.example.com")
        assertTrue(refusals.has(10144, "api.example.com"))
        // Another app may well trust the certificate: it is still read.
        assertFalse(refusals.has(10200, "api.example.com"))
        assertFalse(refusals.has(10144, "cdn.example.com"))
        assertTrue(refusals.mentions("api.example.com"))
        assertFalse(refusals.mentions("cdn.example.com"))
    }

    @Test
    fun withoutTheShellTheHostAloneIsTheKey() {
        val refusals = Refusals()
        refusals.note(null, "api.example.com")
        assertTrue(refusals.has(null, "api.example.com"))
        assertFalse(refusals.has(10144, "api.example.com"))
    }

    @Test
    fun aHostIsTheSameWhateverItsCase() {
        val refusals = Refusals()
        refusals.note(10144, "API.Example.com")
        assertTrue(refusals.has(10144, "api.example.COM"))
        assertTrue(refusals.mentions("Api.Example.Com"))
    }

    @Test
    fun theOldestGoFirstAndARepeatIsNew() {
        val refusals = Refusals(limit = 2)
        refusals.note(1, "a.example")
        refusals.note(1, "b.example")
        refusals.note(1, "a.example")
        refusals.note(1, "c.example")
        assertEquals(listOf("a.example", "c.example"), refusals.all().map { it.host })
    }

    @Test
    fun forgettingOneLeavesTheRest() {
        val refusals = Refusals()
        refusals.note(1, "a.example")
        refusals.note(2, "b.example")
        refusals.forget(Refusals.Refusal(1, "a.example"))
        assertEquals(listOf(Refusals.Refusal(2, "b.example")), refusals.all())
        refusals.clear()
        assertEquals(emptyList(), refusals.all())
    }
}
