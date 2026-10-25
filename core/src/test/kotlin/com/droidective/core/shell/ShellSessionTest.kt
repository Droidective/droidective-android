package com.droidective.core.shell

import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// A real `sh` stands in for `su`: it reads commands on its stdin the same way.
class ShellSessionTest {
    private val session = ShellSession(listOf("sh"), timeoutMillis = 5_000)

    @AfterTest fun close() = session.close()

    @Test
    fun aCommandsOutputAndExitCodeComeBackAsPrinted() {
        assertEquals(ShellResult(0, "hello\n", ""), session.run(ShellCommand("echo hello")))
        assertEquals(
            ShellResult(3, "no newline", "oops\n"),
            session.run(ShellCommand("printf 'no newline'; echo oops >&2; exit 3")),
        )
        assertEquals(
            ShellResult(0, "a\n\nb\n", ""),
            session.run(ShellCommand("printf 'a\\n\\nb\\n'")),
        )
        assertEquals(ShellResult(0, "", ""), session.run(ShellCommand("true")))
    }

    @Test
    fun everyCommandRunsInTheOneShell() {
        // The parent of each command's shell is the session: one su, asked once.
        val parents = List(3) { session.run(ShellCommand("echo \$PPID")).stdout }
        assertEquals(1, parents.toSet().size, parents.toString())
    }

    @Test
    fun aCommandCantChangeTheSessionOrReadItsInput() {
        session.run(ShellCommand("exit 7"))
        session.run(ShellCommand("cd /; X=leaked"))
        assertEquals("unset\n", session.run(ShellCommand("echo \${X:-unset}")).stdout)
        // Nothing on its stdin: a command that reads it ends instead of eating the next command.
        assertEquals(ShellResult(0, "", ""), session.run(ShellCommand("cat")))
        assertEquals("after\n", session.run(ShellCommand("echo after")).stdout)
    }

    @Test
    fun quotesAndUnicodeArriveIntact() {
        val value = "it's \"quoted\" \$HOME `x` 🍵"
        assertEquals("$value\n", session.run(ShellCommand("echo ${shellQuote(value)}")).stdout)
    }

    @Test
    fun aCommandPastItsTimeoutEndsTheSessionAndTheNextStartsAnother() {
        val short = ShellSession(listOf("sh"), timeoutMillis = 300)
        try {
            val before = short.run(ShellCommand("echo \$PPID")).stdout
            val slow = short.run(ShellCommand("sleep 5"))
            assertEquals(ShellSession.TIMED_OUT_EXIT, slow.exitCode)
            assertTrue("Timed out" in slow.stderr, slow.stderr)
            val after = short.run(ShellCommand("echo \$PPID")).stdout
            assertTrue(before != after, "a new session: $before then $after")
        } finally {
            short.close()
        }
    }

    @Test
    fun aShellThatWontStartOrEndsIsUnavailable() {
        assertFailsWith<ShellUnavailableException> {
            ShellSession(listOf("/no/such/su"), 1_000).run(ShellCommand("true"))
        }
        // su refusing exits at once, before answering anything.
        val refused = ShellSession(listOf("sh", "-c", "exit 1"), 1_000)
        assertFailsWith<ShellUnavailableException> { refused.run(ShellCommand("true")) }
    }

    @Test
    fun commandsFromTwoThreadsTakeTurns() {
        val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val threads =
            List(4) { n ->
                thread { repeat(5) { results += session.run(ShellCommand("echo $n-$it")).stdout } }
            }
        threads.forEach { it.join() }
        assertEquals(20, results.size)
        assertEquals(
            (0 until 4).flatMap { n -> (0 until 5).map { "$n-$it\n" } }.toSet(),
            results.toSet(),
        )
    }
}
