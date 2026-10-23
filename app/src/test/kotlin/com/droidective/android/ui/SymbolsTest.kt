package com.droidective.android.ui

import com.droidective.core.apps.AppAction
import com.droidective.core.automations.Action
import com.droidective.core.features.Features
import com.droidective.core.layout.Cells
import com.droidective.core.location.Coordinates
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

    @Test
    fun everyActionAndToolSlotDrawsAnIconThatExists() {
        val actions =
            AppAction.entries.map { Action.ForApp("com.example", it) } +
                listOf(
                    Action.Permission("com.example", "android.permission.CAMERA", grant = true),
                    Action.Permission("com.example", "android.permission.CAMERA", grant = false),
                    Action.RunCommand("c"),
                    Action.OpenApp("com.example", "Example"),
                    Action.OpenLink("https://example.com"),
                    Action.OpenShortcut("Chat", "intent:#Intent;end"),
                    Action.OpenSettings("Wi-Fi", "android.settings.WIFI_SETTINGS"),
                    Action.Notify("Hello"),
                    Action.Wait(1),
                    Action.RunAutomation("a"),
                    Action.SetToggle("no-such-toggle", "on"),
                    Action.SetLocation("London", Coordinates(51.5074, -0.1278)),
                    Action.RealLocation,
                    Action.ReactotronCommand("StreamLab", "resetStore", "Reset store"),
                    Action.ReactotronRestore("4f1c", "Two in the cart", "StreamLab"),
                )
        val icons = actions.map(Cells::actionIcon) + Features.all.map { it.icon } + "bolt"
        assertEquals(emptyList(), icons.filterNot(Symbols.byName::containsKey))
    }
}
