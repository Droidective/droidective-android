package com.droidective.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AgoTest {
    private val now = 1_800_000_000_000L
    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ago(elapsed: Long) = ago(now - elapsed, now)

    @Test
    fun wordsEachStepAtItsBoundaries() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59 * second))
        assertEquals("a minute ago", ago(minute))
        assertEquals("2 minutes ago", ago(2 * minute))
        assertEquals("59 minutes ago", ago(hour - second))
        assertEquals("an hour ago", ago(hour))
        assertEquals("23 hours ago", ago(day - second))
        assertEquals("a day ago", ago(day))
        assertEquals("2 days ago", ago(2 * day))
        assertEquals("400 days ago", ago(400 * day))
    }

    @Test
    fun aMomentInTheFutureIsJustNow() {
        assertEquals("just now", ago(-5 * minute))
    }
}
