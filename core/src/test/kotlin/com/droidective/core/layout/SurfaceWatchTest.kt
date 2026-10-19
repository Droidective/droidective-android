package com.droidective.core.layout

import com.droidective.core.device.AppFreezers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurfaceWatchTest {
    private fun layout(kind: LayoutKind, vararg items: Item) =
        Layout(id = "l-${kind.name.lowercase()}", kind = kind, name = "x", items = items.toList())

    @Test
    fun followsTogglesAndCommandsOnBarsNotificationBarsAndTiles() {
        val watch =
            SurfaceWatch.of(
                shown =
                    listOf(
                        layout(
                            LayoutKind.Bar,
                            Item.Toggle("a", "wifi"),
                            Item.App("b", "com.example", "com.example.Main", "Example"),
                            Item.Command("c", "cmd-1"),
                        ),
                        layout(LayoutKind.Notification, Item.Toggle("d", "bluetooth")),
                    ),
                tiles = listOf(Item.Toggle("e", "flashlight"), Item.Command("f", "cmd-2")),
            )
        assertEquals(setOf("wifi", "bluetooth", "flashlight"), watch.toggleIds)
        assertEquals(setOf("cmd-1", "cmd-2"), watch.commandIds)
    }

    @Test
    fun aFoldersItemsAreReadWhenItOpens() {
        val watch =
            SurfaceWatch.of(
                shown = listOf(layout(LayoutKind.Folder, Item.Toggle("a", "nfc"))),
                tiles = emptyList(),
            )
        assertEquals(SurfaceWatch(emptySet(), emptySet()), watch)
    }

    @Test
    fun onlyTheBrandsWithTheFreezerHoldWidgetTaps() {
        for (brand in listOf("OnePlus", "ONEPLUS", "OPPO", "realme", " oneplus ")) {
            assertTrue(AppFreezers.holdsWidgetTaps(brand), brand)
        }
        for (brand in listOf("Google", "samsung", "Xiaomi", "")) {
            assertFalse(AppFreezers.holdsWidgetTaps(brand), brand)
        }
    }
}
