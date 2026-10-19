package com.droidective.core.toggles

import com.droidective.core.shell.Parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkGenerationTest {
    // `cmd phone get-allowed-network-types-for-users -s 0` on the API 36 emulator, after each mask.
    private val fiveG =
        "GPRS|EDGE|UMTS|CDMA|CDMA - EvDo rev. 0|CDMA - EvDo rev. A|CDMA - 1xRTT|HSDPA|HSUPA|HSPA|" +
            "CDMA - EvDo rev. B|LTE|CDMA - eHRPD|HSPA+|GSM|TD_SCDMA|LTE_CA|NR"
    private val threeG =
        "GPRS|EDGE|UMTS|CDMA|CDMA - EvDo rev. 0|CDMA - EvDo rev. A|CDMA - 1xRTT|HSDPA|HSUPA|HSPA|" +
            "CDMA - EvDo rev. B|CDMA - eHRPD|HSPA+|GSM"

    @Test
    fun theNewestAllowedRadioNamesTheGeneration() {
        assertEquals("5g", Parse.networkGeneration("$fiveG\r\n"))
        assertEquals("4g", Parse.networkGeneration(fiveG.removeSuffix("|NR")))
        assertEquals("4g", Parse.networkGeneration("GSM|LTE_CA"))
        assertEquals("3g", Parse.networkGeneration(threeG))
        assertNull(Parse.networkGeneration(""))
        assertNull(Parse.networkGeneration("set-allowed-network-types-for-users failed"))
        assertNull(Parse.networkGeneration("Invalid subscriptionId:-1"))
    }
}
