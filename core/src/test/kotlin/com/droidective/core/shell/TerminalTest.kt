package com.droidective.core.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalTest {
    @Test
    fun runsTheLineAsTypedInTheQuotedFolderThenReportsHowItEnded() {
        assertEquals(
            "sh -c 'echo \"__droidective_pid__ \$\$\"\n" +
                "cd '\\''/sdcard/my files'\\'' 2>/dev/null\n" +
                "ls -la | grep x # note\n" +
                "echo \"__droidective_end__ \$? \$(pwd)\"'",
            Terminal.finishing("ls -la | grep x # note", "/sdcard/my files").line,
        )
    }

    @Test
    fun aTerminalLineRunsInAGroupThatEndsWithTheApp() {
        val script = Stoppable.guard("cd /data/local/tmp\nsleep 100 &", 4321)
        val wrapped = Terminal.wrap("sleep 100 &", "/data/local/tmp", 4321).line
        assertTrue(wrapped.startsWith("sh -c 's=; command -v setsid"), wrapped)
        assertTrue("[ -d /proc/4321 ] || { end; exit; }" in script, script)
        assertTrue("__droidective_end__" in wrapped, wrapped)
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
        assertEquals(13771, Stoppable.pid("__droidective_pid__ 13771\r"))
        assertNull(Stoppable.pid("uid=0(root)"))
        val stop = Stoppable.stop(13771).line
        assertTrue(stop.startsWith("c=13771\n"), stop)
        assertTrue(stop.endsWith("}\nend"), stop)
    }

    @Test
    fun aStoppableCommandRunsInAGroupOfItsOwnThatEndsWithTheApp() {
        assertEquals(
            "s=; command -v setsid >/dev/null && s=setsid\n" +
                "\$s sh -c 'exec monkey -p '\\''a.b'\\'' 5' & c=\$!\n" +
                "echo \"__droidective_pid__ \$c\"\n" +
                "alive() { kill -0 -- -\$c 2>/dev/null || kill -0 \$c 2>/dev/null; }\n" +
                "end() {\n" +
                "  kill -s INT -- -\$c 2>/dev/null || kill -s INT \$c \$(pgrep -P \$c) 2>/dev/null\n" +
                "  i=0; while alive && [ \$i -lt 10 ]; do sleep 0.5; i=\$((i+1)); done\n" +
                "  kill -s KILL -- -\$c 2>/dev/null || kill -s KILL \$c \$(pgrep -P \$c) 2>/dev/null\n" +
                "}\n" +
                "trap 'trap \"\" INT TERM HUP; end; exit 130' INT TERM HUP\n" +
                "(while alive; do [ -d /proc/4321 ] || { end; exit; }; sleep 1; done)" +
                " </dev/null >/dev/null 2>&1 &\n" +
                "wait \$c",
            Stoppable.guard("exec monkey -p 'a.b' 5", 4321),
        )
        assertEquals(
            "sh -c ${shellQuote(Stoppable.guard("exec monkey -p 'a.b' 5", 4321))}",
            Stoppable.wrap(ShellCommand("monkey -p 'a.b' 5"), 4321).line,
        )
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
