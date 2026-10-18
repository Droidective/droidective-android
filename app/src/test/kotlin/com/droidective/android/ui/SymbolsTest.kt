package com.droidective.android.ui

import com.droidective.core.toggles.Toggles
import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolsTest {
    @Test
    fun everyToggleIconHasBothDrawables() {
        // A missing name means app/symbols.txt needs it and scripts/import-symbols.py a run.
        assertEquals(
            emptyList(),
            Toggles.all.map { it.icon }.filterNot(Symbols.byName::containsKey),
        )
    }

    @Test
    fun everyIconTheSurfacesDrawExists() {
        // Cells.plan, the editor, the navigation and the tiles name these directly.
        val used =
            listOf(
                "folder",
                "link",
                "settings",
                "open_in_new",
                "toggle_on",
                "widgets",
                "shield",
                "search",
                "add",
                "close",
                "arrow_upward",
                "arrow_downward",
                "check_circle",
            )
        assertEquals(emptyList(), used.filterNot(Symbols.byName::containsKey))
    }
}
