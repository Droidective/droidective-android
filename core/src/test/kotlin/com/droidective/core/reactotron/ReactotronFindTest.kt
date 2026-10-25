package com.droidective.core.reactotron

import kotlin.test.Test
import kotlin.test.assertEquals

class ReactotronFindTest {
    private val body =
        """
        {
            "id": 991,
            "status": "placed",
            "items": ["Tea", "tea cake"]
        }
        """
            .trimIndent()

    @Test
    fun everyPlaceTheWordIsInOrderAndInAnyCase() {
        val found = ReactotronFind.matches(body, "TEA")
        assertEquals(listOf("Tea", "tea"), found.map { body.substring(it) })
        assertEquals(found.sortedBy { it.first }, found)
    }

    @Test
    fun theWordIsTrimmedAndItsPlacesDontOverlap() {
        assertEquals(listOf(0..1, 2..3), ReactotronFind.matches("aaaa", " aa "))
        assertEquals(
            listOf("\"status\": \"placed\""),
            ReactotronFind.matches(body, "\"status\": \"placed\"").map { body.substring(it) },
        )
    }

    @Test
    fun nothingToLookForOrNothingThereFindsNothing() {
        assertEquals(emptyList(), ReactotronFind.matches(body, ""))
        assertEquals(emptyList(), ReactotronFind.matches(body, "   "))
        assertEquals(emptyList(), ReactotronFind.matches(body, "refunded"))
        assertEquals(emptyList(), ReactotronFind.matches("", "tea"))
    }

    @Test
    fun aCommonWordStopsAtTheLimit() {
        assertEquals(3, ReactotronFind.matches("a".repeat(10), "a", limit = 3).size)
        assertEquals(ReactotronFind.LIMIT, ReactotronFind.matches("a".repeat(5_000), "a").size)
    }
}
