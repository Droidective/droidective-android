package com.droidective.core.net.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreakpointTest {
    private val orders = Breakpoint("b1", "https://api.example.com/orders/*")

    @Test
    fun aBreakpointStopsAtItsUrlWhateverTheQuery() {
        assertTrue(orders.matches("POST", "https://api.example.com/orders/42?coupon=x"))
        assertFalse(orders.matches("POST", "https://api.example.com/menu"))
    }

    @Test
    fun aMethodNamedIsTheOnlyOneStoppedAt() {
        val posts = orders.copy(method = "POST")
        assertTrue(posts.matches("post", "https://api.example.com/orders/42"))
        assertFalse(posts.matches("GET", "https://api.example.com/orders/42"))
    }

    @Test
    fun oneThatHoldsNeitherWayOrIsOffStopsNothing() {
        assertFalse(orders.copy(enabled = false).matches("GET", "https://api.example.com/orders/1"))
        assertFalse(
            orders
                .copy(requests = false, responses = false)
                .matches("GET", "https://api.example.com/orders/1")
        )
        assertTrue(
            orders
                .copy(requests = false, responses = true)
                .matches("GET", "https://api.example.com/orders/1")
        )
    }

    @Test
    fun theFirstMadeStopsFirst() {
        val wide = Breakpoint("wide", "https://api.example.com/*")
        assertEquals(
            "b1",
            Breakpoint.find(listOf(orders, wide), "GET", "https://api.example.com/orders/1")?.id,
        )
        assertNull(Breakpoint.find(listOf(orders), "GET", "https://cdn.example.com/x"))
    }

    @Test
    fun overlappingBreakpointsHoldWhatEitherHolds() {
        val answers =
            Breakpoint("b2", "https://api.example.com/*", requests = false, responses = true)
        val off = Breakpoint("b3", "https://api.example.com/*", responses = true, enabled = false)

        val found =
            Breakpoint.find(listOf(answers, orders, off), "GET", "https://api.example.com/orders/1")

        assertEquals("b2", found?.id)
        assertTrue(found!!.requests && found.responses)
        // One that is off holds nothing, whatever it says it would hold.
        assertEquals(
            answers,
            Breakpoint.find(listOf(answers, off), "GET", "https://api.example.com/menu"),
        )
    }

    @Test
    fun oneMadeFromAFlowStopsAtItsRequestsWhateverTheirQuery() {
        val made = Breakpoint.of(flow(method = "post", path = "/orders/42?coupon=x"), "b2")
        assertEquals(Breakpoint("b2", "https://api.example.com/orders/42", "POST"), made)
        assertNull(made.problem())
        assertTrue(made.matches("POST", "https://api.example.com/orders/42?coupon=y"))
    }

    @Test
    fun oneThatCantBeKeptSaysWhy() {
        listOf(
                orders.copy(url = "api.example.com/orders"),
                orders.copy(url = "https:///orders"),
                orders.copy(method = "GET /"),
                orders.copy(requests = false, responses = false),
            )
            .forEach { assertTrue(it.problem() != null, it.toString()) }
        assertNull(orders.copy(method = "PATCH", requests = false, responses = true).problem())
    }
}
