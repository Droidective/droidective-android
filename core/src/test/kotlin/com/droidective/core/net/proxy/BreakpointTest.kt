package com.droidective.core.net.proxy

import kotlin.io.path.createTempDirectory
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
    fun theBreakpointsAreKept() {
        val repository = BreakpointRepository(createTempDirectory("points").toFile())
        assertEquals(emptyList(), repository.all())
        repository.save(listOf(orders))
        assertEquals(listOf(orders), repository.all())
    }
}
