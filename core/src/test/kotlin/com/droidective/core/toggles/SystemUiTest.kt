package com.droidective.core.toggles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemUiTest {
    private val gboard =
        "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
    private val swift = "com.touchtype.swiftkey/com.touchtype.KeyboardService"
    private val adbKeyboard = "com.android.adbkeyboard/.AdbIME"

    @Test
    fun cyclesToTheNextEnabledKeyboardAndWraps() {
        val enabled = "$gboard;-921088104:$swift:$adbKeyboard"
        assertEquals(swift, Keyboards.next(enabled, gboard))
        assertEquals(gboard, Keyboards.next(enabled, adbKeyboard))
    }

    @Test
    fun startsAtTheFirstWhenTheCurrentOneIsntEnabled() {
        assertEquals(gboard, Keyboards.next("$gboard:$swift", "gone/.Ime"))
    }

    @Test
    fun hasNothingToSwitchToWithOneKeyboard() {
        assertNull(Keyboards.next(gboard, gboard))
        assertNull(Keyboards.next(null, null))
        assertNull(Keyboards.next("", gboard))
    }

    @Test
    fun demoModeSendsTheMacsBroadcastsInOrder() {
        assertEquals(
            listOf("enter", "clock", "battery", "network", "notifications"),
            DemoMode.enter.map { it["command"] },
        )
        assertEquals("0941", DemoMode.enter[1]["hhmm"])
        assertEquals(mapOf("command" to "exit"), DemoMode.exit)
    }
}
