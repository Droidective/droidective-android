package com.droidective.android.ui.tools

import com.droidective.android.ui.Symbols
import com.droidective.core.features.Features
import com.droidective.core.toggles.Toggles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolScreensTest {
    @Test
    fun everyToolHasAScreenAndNothingElseDoes() {
        // A tool missing here would open nothing; an extra key is a screen nobody can reach.
        assertEquals(Features.all.map { it.id }.toSet(), toolScreens.keys)
    }

    @Test
    fun everyToolIconExists() {
        assertEquals(
            emptyList(),
            Features.all.map { it.icon }.filterNot(Symbols.byName::containsKey),
        )
    }

    @Test
    fun everyToggleAToolShowsIsARealBuiltToggle() {
        // Every list on ToolToggles, so a new section is checked without being named here.
        val ids =
            ToolToggles::class
                .java
                .declaredFields
                .filter { List::class.java.isAssignableFrom(it.type) }
                .flatMap { field ->
                    field.isAccessible = true
                    (field.get(ToolToggles) as List<*>).map { it as String }
                }
        assertTrue(ids.size > 10, "found ${ids.size} ids; ToolToggles' lists weren't read")
        assertEquals(emptyList(), ids.filterNot { Toggles.byId(it)?.isBuilt == true })
    }
}
