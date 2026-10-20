package com.droidective.core.apps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Against `appops get` for this app on an API 36 emulator (fixtures/appops/). */
class AppOpsTest {
    private val ops =
        AppOps.parse(
            File(System.getProperty("fixtures.dir"), "appops/droidective-api36.txt").readText()
        )

    @Test
    fun readsEveryOpWithItsModeAndTimes() {
        assertEquals(50, ops.size)
        val first = ops.first()
        assertEquals(AppOp("COARSE_LOCATION", "ignore", null, null), first)
        val overlay = ops.first { it.name == "SYSTEM_ALERT_WINDOW" }
        assertEquals("allow", overlay.mode)
        assertEquals("+16m11s450ms", overlay.lastUsed)
        assertNull(overlay.lastRefused)
        val audio = ops.first { it.name == "WRITE_MEDIA_AUDIO" }
        assertEquals("deny", audio.mode)
        assertEquals("+2h6m13s797ms", audio.lastRefused)
    }

    @Test
    fun aNameListedTwiceKeepsTheUidsLine() {
        val parsed =
            AppOps.parse("Uid mode: RUN_ANY_IN_BACKGROUND: ignore\nRUN_ANY_IN_BACKGROUND: allow\n")
        assertEquals(listOf(AppOp("RUN_ANY_IN_BACKGROUND", "ignore", null, null)), parsed)
        assertEquals(emptyList(), AppOps.parse("Error: No UID for nope in user 0\n"))
    }

    @Test
    fun setsOnlyRealOpsAndModesQuoted() {
        assertEquals(
            "appops set 'com.example.app' RUN_ANY_IN_BACKGROUND ignore",
            AppOps.set("com.example.app", "RUN_ANY_IN_BACKGROUND", "ignore").line,
        )
        assertEquals("appops reset 'com.example.app'", AppOps.reset("com.example.app").line)
        assertEquals("appops get 'com.example.app'", AppOps.get("com.example.app").line)
        assertFailsWith<IllegalArgumentException> {
            AppOps.set("com.example.app", "X; reboot", "allow")
        }
        assertFailsWith<IllegalArgumentException> { AppOps.set("com.example.app", "CAMERA", "yes") }
        assertFailsWith<IllegalArgumentException> { AppOps.set("a;b", "CAMERA", "allow") }
    }

    @Test
    fun theKnownOpsAreRealOpNames() {
        AppOps.known.forEach { assertEquals(true, Regex("^[A-Z_]+$").matches(it.name), it.name) }
        assertEquals(AppOps.known.size, AppOps.known.map { it.name }.toSet().size)
    }
}
