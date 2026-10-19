package com.droidective.core.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallTimesTest {
    @Test
    fun aSystemImageClockIsNoInstallTime() {
        // A OnePlus Pad's preinstalled Calculator: 1970-01-07 07:01:44.
        assertNull(InstallTimes.real(543_704_000L))
        assertNull(InstallTimes.real(0))
        assertNull(InstallTimes.real(InstallTimes.ANDROID_RELEASE_MILLIS - 1))
    }

    @Test
    fun aRealInstallTimeIsKept() {
        assertEquals(1_792_454_009_000L, InstallTimes.real(1_792_454_009_000L))
        assertEquals(
            InstallTimes.ANDROID_RELEASE_MILLIS,
            InstallTimes.real(InstallTimes.ANDROID_RELEASE_MILLIS),
        )
    }
}
