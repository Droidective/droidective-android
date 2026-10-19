package com.droidective.core.layout

import com.droidective.core.Access
import com.droidective.core.AccessLevel
import com.droidective.core.ShellProvider
import com.droidective.core.apps.AppAction
import com.droidective.core.automations.Action
import com.droidective.core.automations.Automation
import com.droidective.core.automations.Event
import com.droidective.core.automations.Trigger
import com.droidective.core.automations.level
import com.droidective.core.commands.CustomCommand
import com.droidective.core.store.JsonFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slots that run an automation, one action, or open a tool. */
class SlotItemsTest {
    private val root = Access(granted = true, shell = ShellProvider.Su)
    private val noShell = Access(granted = false, shell = null)
    private val restart =
        Automation(
            id = "restart",
            name = "Restart my app",
            trigger = Trigger.On(Event.Manual),
            actions = listOf(Action.ForApp("com.example", AppAction.ForceStop)),
        )

    private fun plan(item: Item, state: String? = null, access: Access = root) =
        Cells.plan(
            item,
            state,
            access,
            folderName = { null },
            command = { id -> CustomCommand(id, "Reboot to recovery", "reboot recovery") },
            automation = { id -> restart.takeIf { it.id == id } },
        )

    @Test
    fun anAutomationSlotRunsItByName() {
        val cell = plan(Item.Automation("a", "restart"))
        assertEquals("Restart my app", cell?.label)
        assertEquals(Tap.RunAutomation("restart"), cell?.tap)
        assertNull(plan(Item.Automation("b", "deleted")), "a deleted automation draws nothing")
    }

    @Test
    fun anActionSlotRunsWhereTheLevelIsThereAndSetsUpWhereItIsnt() {
        val stop = Action.ForApp("com.example", AppAction.ForceStop)
        val inPlace = plan(Item.Action("a", stop, "Force stop Example"))
        assertEquals(Tap.RunAction(stop, "Force stop Example"), inPlace?.tap)
        assertEquals(CellIcon.Symbol("block"), inPlace?.icon)
        assertFalse(inPlace!!.degraded)

        val missing = plan(Item.Action("a", stop, "Force stop Example"), access = noShell)
        assertEquals(Tap.Open(Target.Setup(AccessLevel.Shell)), missing?.tap)
        assertTrue(missing!!.degraded)
    }

    @Test
    fun aSlotThatSetsAToggleLightsUpWhileTheToggleIsInThatState() {
        val half = Action.SetToggle("animation-scale", "0.5")
        assertTrue(plan(Item.Action("a", half, "Animations 0.5×"), state = "0.5")!!.active)
        assertFalse(plan(Item.Action("a", half, "Animations 0.5×"), state = "1")!!.active)
        assertEquals(CellIcon.Symbol("animation"), plan(Item.Action("a", half, "x"))?.icon)
        assertNull(plan(Item.Action("b", Action.SetToggle("gone", "on"), "Gone")))
    }

    @Test
    fun aToolSlotOpensTheTool() {
        val cell = plan(Item.Tool("a", "logcat"))
        assertEquals("Logcat", cell?.label)
        assertEquals(Tap.Open(Target.Tool("logcat")), cell?.tap)
        assertNull(plan(Item.Tool("b", "no-such-tool")))
    }

    @Test
    fun anActionNeedsWhatItsToggleOrTheShellNeeds() {
        assertEquals(AccessLevel.Granted, Action.SetToggle("animation-scale", "1").level())
        assertEquals(AccessLevel.Shell, Action.ForApp("a.b", AppAction.ClearData).level())
        assertEquals(AccessLevel.Shell, Action.Permission("a.b", "c", grant = true).level())
        assertEquals(AccessLevel.Shell, Action.RunCommand("c").level())
        assertEquals(AccessLevel.App, Action.OpenLink("https://example.com").level())
        assertEquals(AccessLevel.App, Action.RunAutomation("a").level())
    }

    @Test
    fun theNewSlotsKeepAStableFileFormat() {
        val layout =
            Layout(
                id = "home",
                kind = LayoutKind.Bar,
                name = "Home",
                items =
                    listOf(
                        Item.Automation("a", "restart"),
                        Item.Action("b", Action.SetToggle("animation-scale", "0.5"), "Half"),
                        Item.Tool("c", "logcat"),
                    ),
            )
        val json = JsonFileStore.json.encodeToString(Layout.serializer(), layout)
        assertTrue("\"type\": \"automation\"" in json, json)
        assertTrue("\"type\": \"action\"" in json && "\"type\": \"set-toggle\"" in json, json)
        assertTrue("\"type\": \"tool\"" in json, json)
        assertEquals(layout, JsonFileStore.json.decodeFromString(Layout.serializer(), json))
    }

    @Test
    fun aSurfaceFollowsTheTogglesItsActionsSet() {
        val watch =
            SurfaceWatch.of(
                shown =
                    listOf(
                        Layout(
                            "bar",
                            LayoutKind.Bar,
                            "Bar",
                            listOf(Item.Action("a", Action.SetToggle("wifi", "off"), "Wi-Fi off")),
                        )
                    ),
                tiles = emptyList(),
            )
        assertEquals(setOf("wifi"), watch.toggleIds)
    }
}
