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
    fun aFolderPreviewsWithItsColoursAloneOnAClearGrid() {
        val preview = Layout("w", LayoutKind.Folder, "Folder", style = styled).previewStyle()
        assertEquals(Style(background = 0, iconOn = 0xFF29B6F6, iconOff = 0xFF5F6368), preview)
    }

    @Test
    fun aNotificationBarPreviewsWhatTheShadeDraws() {
        val preview = Layout("n", LayoutKind.Notification, "Bar", style = styled).previewStyle()
        assertEquals(0L, preview.background, "on the shade's own colour until it's filled")
        assertEquals(Display.List, preview.display)
        assertEquals(true, preview.labels)
        assertEquals(40, preview.iconSize)
        val filled = styled.copy(filled = true, iconSize = 64)
        val previewFilled =
            Layout("n", LayoutKind.Notification, "Bar", style = filled).previewStyle()
        assertEquals(0xFF000000, previewFilled.background)
        assertEquals(40, previewFilled.iconSize, "no bigger than the 48 dp row holds")
    }

    @Test
    fun aNotificationBarAsItAlwaysWasDrawsTheSame() {
        // Every notification bar made before it could be styled has the default style.
        val before = Style()
        assertEquals(NotificationLook.Expanded.Row, NotificationLook.expanded(before))
        assertEquals(28, NotificationLook.iconSize(before))
        assertEquals(10, NotificationLook.rowPadding(before), "the 10 dp the row always had")
        assertEquals(false, before.filled)
    }

    @Test
    fun theExpandedNotificationShowsWhatTheStyleAsksFor() {
        assertEquals(
            NotificationLook.Expanded.Labelled,
            NotificationLook.expanded(Style(labels = true)),
        )
        assertEquals(
            NotificationLook.Expanded.Labelled,
            NotificationLook.expanded(Style(stateText = true)),
        )
        assertEquals(
            NotificationLook.Expanded.List,
            NotificationLook.expanded(Style(display = Display.List)),
        )
        assertEquals(4, NotificationLook.rowPadding(Style(iconSize = 64)), "clamped to 40")
        assertEquals(16, NotificationLook.rowPadding(Style(iconSize = 8)), "clamped to 16")
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
