package com.droidective.core.layout

import com.droidective.core.store.JsonFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetLookTest {
    private val cell =
        Cell(
            key = "a",
            label = "Wi-Fi",
            icon = CellIcon.Symbol("wifi"),
            active = false,
            degraded = false,
            tap = Tap.RunToggle("wifi"),
            state = "Off",
        )

    @Test
    fun aLayoutSavedBeforeStylesGrewDrawsAsItDid() {
        // A file from 0.1.x: four style fields and no looks.
        val old =
            """{"id":"home","kind":"bar","name":"Home","items":[],"style":""" +
                """{"background":4028703522,"iconOn":4285451295,"iconOff":4287468687,""" +
                """"labels":true},"editedAt":3}"""
        val layout = JsonFileStore.json.decodeFromString(Layout.serializer(), old)
        assertEquals(Style(labels = true), layout.style)
        assertEquals(emptyMap(), layout.looks)
    }

    @Test
    fun aSlotsOwnLookReplacesOnlyWhatItSets() {
        val styled = Cells.styled(cell, Look(label = "Net", color = 0xFFEF5350))
        assertEquals("Net", styled.label)
        assertEquals(CellIcon.Symbol("wifi"), styled.icon)
        assertEquals(0xFFEF5350, styled.color)
        assertEquals(
            "Wi-Fi",
            Cells.styled(cell, Look(label = " ")).label,
            "a blank label is no label",
        )
        assertEquals(
            CellIcon.Symbol("rocket_launch"),
            Cells.styled(cell, Look(icon = "rocket_launch")).icon,
        )
        assertEquals(cell, Cells.styled(cell, null))
    }

    @Test
    fun aSlotsOwnColourShowsWhileOnAndAlwaysWithoutAState() {
        val on = 1L
        val off = 2L
        val red = 3L
        assertEquals(off, cell.copy(color = red).tint(on, off), "off keeps the off colour")
        assertEquals(red, cell.copy(color = red, active = true).tint(on, off))
        assertEquals(on, cell.copy(active = true).tint(on, off))
        val tool = cell.copy(state = null, color = red, tap = Tap.Open(Target.Tool("logcat")))
        assertEquals(red, tool.tint(on, off), "a slot with no state always shows its colour")
    }

    @Test
    fun relookingAndRemovingKeepLooksTidy() {
        val layout = Layout("home", LayoutKind.Bar, "Home", listOf(Item.Toggle("a", "wifi")))
        val looked = LayoutEdits.relook(layout, "a", Look(label = "Net"), now = 5)
        assertEquals(mapOf("a" to Look(label = "Net")), looked.looks)
        assertEquals(5, looked.editedAt)
        assertEquals(emptyMap(), LayoutEdits.relook(looked, "a", Look(), now = 6).looks)
        assertEquals(emptyMap(), LayoutEdits.remove(looked, "a", now = 7).looks)
    }

    @Test
    fun restylingKeepsEveryNumberDrawable() {
        val wild =
            Style(columns = 99, iconSize = 2, cornerRadius = -4, padding = 90, labelSize = 40)
        val kept = LayoutEdits.restyle(Layout("h", LayoutKind.Bar, "H"), wild, now = 1).style
        assertEquals(Style.MAX_COLUMNS, kept.columns)
        assertEquals(Style.ICON_SIZES.first, kept.iconSize)
        assertEquals(Style.CORNERS.first, kept.cornerRadius)
        assertEquals(Style.PADDINGS.last, kept.padding)
        assertEquals(Style.LABEL_SIZES.last, kept.labelSize)
    }

    @Test
    fun columnsFitTheWidthUnlessFixedAndNeverOutnumberTheSlots() {
        val style = Style()
        assertEquals(6, WidgetGrid.columns(widthDp = 400f, count = 6, style))
        assertEquals(3, WidgetGrid.columns(widthDp = 160f, count = 6, style))
        assertEquals(1, WidgetGrid.columns(widthDp = 10f, count = 6, style))
        assertEquals(2, WidgetGrid.columns(widthDp = 400f, count = 6, style.copy(columns = 2)))
        assertEquals(3, WidgetGrid.columns(widthDp = 400f, count = 3, style.copy(columns = 5)))
        // Bigger icons need wider cells, so fewer fit.
        assertEquals(4, WidgetGrid.columns(widthDp = 400f, count = 9, style.copy(iconSize = 64)))
    }

    @Test
    fun aPresetChangesColoursAndKeepsTheArrangement() {
        val arranged = Style(display = Display.List, iconSize = 40, labels = true)
        val light = StylePreset.Light.applyTo(arranged)
        assertEquals(Display.List, light.display)
        assertEquals(40, light.iconSize)
        assertTrue(StylePreset.Light.isOn(light))
        assertFalse(StylePreset.Droidective.isOn(light))
        assertTrue(StylePreset.Droidective.isOn(Style()))
        assertTrue(StylePreset.Wallpaper.applyTo(light).dynamicColors)
        assertFalse(StylePreset.Black.applyTo(StylePreset.Wallpaper.applyTo(light)).dynamicColors)
    }
}
