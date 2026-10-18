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
}
