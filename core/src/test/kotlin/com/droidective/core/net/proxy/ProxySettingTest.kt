package com.droidective.core.net.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxySettingTest {
    @Test
    fun pointingHereIsTheLoopbackAddressAndPort() {
        assertEquals("127.0.0.1:8888", ProxySetting.here(8_888))
    }

    @Test
    fun recognisesItselfHoweverTheValueWasWritten() {
        assertTrue(ProxySetting.pointsHere("127.0.0.1:8888", 8_888))
        assertTrue(ProxySetting.pointsHere(" 127.0.0.1:8888\n", 8_888))
        assertTrue(ProxySetting.pointsHere("localhost:8888", 8_888))
        assertTrue(ProxySetting.pointsHere("LOCALHOST:8888", 8_888))
    }

    @Test
    fun anythingElseIsNotHere() {
        listOf(null, "", ":0", "null", "127.0.0.1:9090", "10.0.0.12:8888", "127.0.0.1").forEach {
            assertFalse(ProxySetting.pointsHere(it, 8_888), "$it")
        }
    }

    @Test
    fun stoppingPutsBackTheProxySomeoneHadBefore() {
        assertEquals("10.0.0.12:8888", ProxySetting.restore("10.0.0.12:8888", 8_888))
        assertEquals("proxy.local:3128", ProxySetting.restore(" proxy.local:3128 ", 8_888))
        // Another tool on the phone itself is still somebody else's proxy.
        assertEquals("127.0.0.1:9090", ProxySetting.restore("127.0.0.1:9090", 8_888))
    }

    @Test
    fun stoppingWithNothingBeforeClearsItTheWayAndroidReads() {
        listOf(null, "", "  ", ":0", "null").forEach {
            assertEquals(":0", ProxySetting.restore(it, 8_888), "before was \"$it\"")
        }
    }

    @Test
    fun aLeftoverPointingHereIsNeverPutBack() {
        // The run before never cleaned up. Restoring that would leave the phone with no network.
        assertEquals(":0", ProxySetting.restore("127.0.0.1:8888", 8_888))
        assertEquals(":0", ProxySetting.restore("localhost:8888", 8_888))
    }
}
