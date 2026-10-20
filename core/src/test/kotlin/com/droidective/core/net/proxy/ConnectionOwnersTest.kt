package com.droidective.core.net.proxy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Against `cat /proc/net/tcp /proc/net/tcp6` from an API 36 emulator (fixtures/proc/). Remote
 * addresses are blanked; the parser reads the local port, the state and the uid.
 */
class ConnectionOwnersTest {
    private val table =
        File(System.getProperty("fixtures.dir"), "proc/net-tcp-api36.txt").readText()

    @Test
    fun findsTheUidHoldingAPort() {
        assertEquals(1002, ConnectionOwners.owner(table, 8872))
        // An IPv6 row is four times as wide and says the same thing.
        assertEquals(10219, ConnectionOwners.owner(table, 60300))
        assertEquals(10144, ConnectionOwners.owner(table, 54376))
    }

    @Test
    fun aPortNobodyHoldsHasNoOwner() {
        assertNull(ConnectionOwners.owner(table, 1))
    }

    @Test
    fun theLinesNamingTheColumnsAreNotSockets() {
        // Both files are read at once, so a header turns up in the middle of the table too.
        assertEquals(mapOf(54376 to 10144), ConnectionOwners.established(table))
    }

    @Test
    fun anEstablishedSocketBeatsOneOnItsWayOut() {
        val closing = "   1: 1002000A:1F90 00000000:01BB 08 0:0 0:0 0 10000 0 1 1"
        val live = "   2: 1002000A:1F90 00000000:01BB 01 0:0 0:0 0 10144 0 1 1"
        assertEquals(10144, ConnectionOwners.owner("$closing\n$live", 8080))
        assertEquals(10144, ConnectionOwners.owner("$live\n$closing", 8080))
        // With nothing established, the socket on its way out is the best answer there is.
        assertEquals(10000, ConnectionOwners.owner(closing, 8080))
    }

    @Test
    fun nonsenseIsSkippedRatherThanThrown() {
        assertNull(ConnectionOwners.owner("", 8080))
        assertNull(ConnectionOwners.owner("not a table at all", 8080))
        assertNull(ConnectionOwners.owner("   1: short 00000000:01BB 01", 8080))
    }

    @Test
    fun theCommandReadsBothFilesAndSurvivesOneBeingMissing() {
        assertEquals("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null", ConnectionOwners.read.line)
    }
}
