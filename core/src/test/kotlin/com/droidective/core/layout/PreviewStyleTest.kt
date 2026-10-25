package com.droidective.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewStyleTest {
    private val styled =
        Style(
            background = 0xFF000000,
            iconOn = 0xFF29B6F6,
            iconOff = 0xFF5F6368,
            labels = true,
            display = Display.List,
            iconSize = 40,
        )

    @Test
    fun onlyAHomeScreenWidgetDrawsWithTheWholeStyle() {
        assertEquals(
            listOf(LayoutKind.Bar),
            LayoutKind.entries.filter { it.usesWholeStyle },
        )
    }

    @Test
    fun aHomeScreenWidgetPreviewsAsItIsStyled() {
        val layout = Layout("w", LayoutKind.Bar, "Bar", style = styled)
        assertEquals(styled, layout.previewStyle())
    }

    @Test
    fun aNotificationBarOrFolderPreviewsWithItsColoursAloneOnAClearGrid() {
        for (kind in listOf(LayoutKind.Notification, LayoutKind.Folder)) {
            val preview = Layout("w", kind, "Bar", style = styled).previewStyle()
            assertEquals(Style(background = 0, iconOn = 0xFF29B6F6, iconOff = 0xFF5F6368), preview)
        }
    }

    @Test
    fun theFirstFreeTileSlotIsTheLowestNumberNotTaken() {
        assertEquals(1, TileSlotNumbers.firstFree(emptySet(), 12))
        assertEquals(3, TileSlotNumbers.firstFree(setOf(1, 2, 4), 12))
        assertEquals(12, TileSlotNumbers.firstFree((1..11).toSet(), 12))
    }

    @Test
    fun thereIsNoFreeTileSlotWhenEveryOneIsTaken() {
        assertNull(TileSlotNumbers.firstFree((1..12).toSet(), 12))
    }
}
