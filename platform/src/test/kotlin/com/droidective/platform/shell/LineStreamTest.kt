package com.droidective.platform.shell

import kotlin.test.assertEquals
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class LineStreamTest {
    private fun sh(script: String) = LineStream.process(listOf("sh", "-c", script))

    @Test
    fun emitsEachLineUntilTheCommandExits() = runBlocking {
        assertEquals(listOf("a", "b", "c"), sh("printf 'a\\nb\\nc\\n'").toList())
    }

    @Test
    fun stderrArrivesInTheSameStream() = runBlocking {
        assertEquals(listOf("out", "err"), sh("echo out; sleep 0.1; echo err 1>&2").toList())
    }

    @Test
    fun stoppingEndsACommandThatWouldRunForever() = runBlocking {
        // `yes` never exits on its own; taking three lines has to stop it, not hang.
        withTimeout(5_000) { assertEquals(listOf("y", "y", "y"), sh("yes").take(3).toList()) }
    }

    @Test
    fun aQuietCommandStopsWhenCollectingDoes() = runBlocking {
        withTimeout(5_000) {
            assertEquals(listOf("ready"), sh("echo ready; sleep 30").take(1).toList())
        }
    }
}
