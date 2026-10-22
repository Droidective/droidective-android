package com.droidective.core.toggles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloatingControlsTest {
    @Test
    fun everyControlIsARealBuiltToggle() {
        FloatingControls.controls.forEach {
            assertTrue(Toggles.byId(it.toggleId)?.isBuilt == true, it.toggleId)
        }
        assertEquals(
            FloatingControls.controls.size,
            FloatingControls.controls.map { it.toggleId }.toSet().size,
        )
    }

    @Test
    fun whatCapturesTheScreenGetsTheControlsOutOfIt() {
        val hides = FloatingControls.controls.associate { it.toggleId to it.hide }
        listOf("screenshot", "clean-screenshot", "capture-views").forEach {
            assertTrue(hides.getValue(it) is FloatingControls.Hide.For, it)
        }
        // A recording would film them for as long as it runs.
        assertEquals(FloatingControls.Hide.WhileOn, hides.getValue("record-screen"))
        assertEquals(FloatingControls.Hide.Never, hides.getValue("traffic-proxy"))
    }

    @Test
    fun theControlsAreNotAmongTheirOwnButtons() {
        assertTrue(FloatingControls.controls.none { it.toggleId == "floating-controls" })
    }
}
