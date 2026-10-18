package com.droidective.android.ui.automations

import com.droidective.core.automations.AutomationRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExamplesTest {
    @Test
    fun everyExampleIsValidAndArrivesSwitchedOff() {
        examples.forEach { example ->
            assertEquals(emptyList(), AutomationRules.problems(example), example.name)
            assertTrue(!example.enabled, example.name)
        }
    }

    @Test
    fun theMacCommandNamesTheReceiverAndTheId() {
        assertEquals(
            "adb shell am broadcast -n com.droidective.android/.RunAutomation --es id bedtime",
            macCommand("bedtime"),
        )
    }
}
