package com.droidective.core.toggles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapNotesTest {
    private fun toggle(id: String) = Toggles.byId(id)!!

    @Test
    fun aToggleSaysTheStateItIsInNow() {
        assertEquals("Wi-Fi: On", TapNotes.applied(toggle("wifi"), "on"))
        assertEquals("Wi-Fi: Off", TapNotes.applied(toggle("wifi"), "off"))
        assertEquals("Animation scale: 0.5×", TapNotes.applied(toggle("animation-scale"), "0.5"))
    }

    @Test
    fun anActionSaysItIsDone() {
        assertEquals("Lock screen: done", TapNotes.applied(toggle("lock-screen"), "run"))
    }

    @Test
    fun whatCapturesTheScreenSaysNothingThatWouldBeCaptured() {
        listOf("screenshot", "clean-screenshot", "record-screen", "capture-views").forEach {
            assertNull(TapNotes.applied(toggle(it), "on"), it)
        }
        assertEquals("Debugging proxy: On", TapNotes.applied(toggle("traffic-proxy"), "on"))
    }

    @Test
    fun onlyWirelessDebuggingTurningOnAddsTheAddress() {
        assertTrue(TapNotes.addsAdbAddress(toggle("wireless-debugging"), "on"))
        assertFalse(TapNotes.addsAdbAddress(toggle("wireless-debugging"), "off"))
        assertFalse(TapNotes.addsAdbAddress(toggle("wifi"), "on"))
    }
}
