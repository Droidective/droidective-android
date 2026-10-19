package com.droidective.core.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalTest {
    @Test
    fun runsTheLineAsTypedInTheQuotedFolderThenReportsHowItEnded() {
        assertEquals(
            "sh -c 'echo \"__droidective_pid__ \$\$\"\n" +
                "cd '\\''/sdcard/my files'\\'' 2>/dev/null\n" +
                "ls -la | grep x # note\n" +
                "echo \"__droidective_end__ \$? \$(pwd)\"'",
            Terminal.wrap("ls -la | grep x # note", "/sdcard/my files").line,
        )
    }

    @Test
    fun readsTheEndingAndNothingElse() {
        assertEquals(
            Ending(0, "/data/local/tmp"),
            Terminal.ending("__droidective_end__ 0 /data/local/tmp"),
        )
        assertEquals(
            Ending(127, "/sdcard/my files"),
            Terminal.ending("__droidective_end__ 127 /sdcard/my files\r"),
        )
        assertNull(Terminal.ending("total 40"))
        assertNull(Terminal.ending("echo __droidective_end__"))
    }

    @Test
    fun readsTheWrappersPidAndStopsItsChildrenThenIt() {
        assertEquals(13771, Terminal.pid("__droidective_pid__ 13771\r"))
        assertNull(Terminal.pid("uid=0(root)"))
        assertEquals("kill -TERM \$(pgrep -P 13771) 13771", Terminal.stop(13771).line)
    }

    @Test
    fun historyWalksBackAndForthLikeAShell() {
        val history = TerminalHistory()
        assertNull(history.older())
        history.add("ls")
        history.add("  ")
        history.add("pwd")
        history.add("pwd")
        assertEquals(listOf("ls", "pwd"), history.all)
        assertEquals("pwd", history.older())
        assertEquals("ls", history.older())
        assertEquals("ls", history.older(), "stays on the oldest")
        assertEquals("pwd", history.newer())
        assertEquals("", history.newer(), "past the newest the prompt is blank")
    }

    @Test
    fun historyKeepsOnlyTheNewest() {
        val history = TerminalHistory(limit = 2)
        listOf("a", "b", "c").forEach(history::add)
        assertEquals(listOf("b", "c"), history.all)
    }
}
