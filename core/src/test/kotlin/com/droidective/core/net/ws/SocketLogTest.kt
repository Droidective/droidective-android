package com.droidective.core.net.ws

import kotlin.test.Test
import kotlin.test.assertEquals

class SocketLogTest {
    private fun said(text: String, fromApp: Boolean = false) =
        SocketMessage(
            fromApp,
            0,
            SocketMessage.Kind.Text,
            text.toByteArray(),
            text.length.toLong(),
            truncated = false,
        )

    @Test
    fun eachSocketsMessagesAreItsOwnInOrder() {
        val log = SocketLog()
        log.put(1, said("a", fromApp = true))
        log.put(2, said("x"))
        log.put(1, said("b"))

        assertEquals(listOf("a", "b"), log.of(1).map { it.text })
        assertEquals(listOf("x"), log.of(2).map { it.text })
        assertEquals(2, log.count(1))
        assertEquals(emptyList(), log.of(3))
    }

    @Test
    fun theOldestGoFirstWhicheverSocketTheyCameFrom() {
        val log = SocketLog(maxMessages = 3)
        log.put(1, said("first"))
        log.put(2, said("second"))
        log.put(1, said("third"))
        log.put(2, said("fourth"))

        assertEquals(listOf("third"), log.of(1).map { it.text })
        assertEquals(listOf("second", "fourth"), log.of(2).map { it.text })
    }

    @Test
    fun bytesBoundItToo() {
        val log = SocketLog(maxBytes = 3 * (1_000 + 64L))
        repeat(5) { log.put(1, said("x".repeat(1_000))) }

        assertEquals(3, log.count(1))
        assertEquals(3 * (1_000 + 64L), log.heldBytes())
    }

    @Test
    fun clearingForgetsEverything() {
        val log = SocketLog()
        log.put(1, said("a"))
        log.clear()

        assertEquals(0, log.count(1))
        assertEquals(0, log.heldBytes())
    }
}
