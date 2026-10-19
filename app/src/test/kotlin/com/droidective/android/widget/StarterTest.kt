package com.droidective.android.widget

import com.droidective.core.toggles.Toggles
import kotlin.test.Test
import kotlin.test.assertEquals

class StarterTest {
    @Test
    fun everyWidgetKindStartsWithTogglesThatExist() {
        // A typo here would place a widget with a slot missing and no error anywhere.
        val missing = Starter.entries.flatMap { it.toggles }.filter { Toggles.byId(it) == null }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun everyWidgetKindHasAReceiverAndEachReceiverOneKind() {
        assertEquals(Starter.entries.toSet(), WidgetReceiver.kinds.values.toSet())
        assertEquals(Starter.entries.size, WidgetReceiver.kinds.size)
    }
}
