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
import com.droidective.core.location.Coordinates
import com.droidective.core.network.NetworkCards
import com.droidective.core.network.NetworkFact
import com.droidective.core.network.NetworkNow
import com.droidective.core.network.Transport
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
    fun aPlaceOrAReactotronCommandRunsInPlaceWithoutTheShell() {
        listOf(
                Action.SetLocation("London", Coordinates(51.5074, -0.1278)) to "location_on",
                Action.RealLocation to "location_off",
                Action.ReactotronCommand("StreamLab", "resetStore", "Reset store") to "data_object",
                Action.ReactotronRestore("4f1c", "Two in the cart", "StreamLab") to "history",
                Action.ReactotronDispatch("StreamLab", """{"type":"logout"}""") to "play_circle",
            )
            .forEach { (action, icon) ->
                val cell = plan(Item.Action("a", action, "Slot"), access = noShell)
                assertEquals(Tap.RunAction(action, "Slot"), cell?.tap)
                assertEquals(CellIcon.Symbol(icon), cell?.icon)
                assertFalse(cell!!.degraded)
            }
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
    fun aDeveloperSettingsSlotOpensThePhonesDeveloperOptions() {
        val cell = plan(Item.Tool("a", "dev-settings"))
        assertEquals("Developer Settings", cell?.label)
        assertEquals(
            Tap.Open(Target.SystemScreen("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")),
            cell?.tap,
        )
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
    fun aNetworkSlotShowsWhatItIsForAndCopiesIt() {
        val now = NetworkNow(Transport.Wifi, "Home 5G", "192.168.1.23", "203.0.113.7")
        fun network(fact: NetworkFact, known: NetworkNow? = now) =
            Cells.plan(
                Item.Network("n", fact),
                state = null,
                noShell,
                folderName = { null },
                network = { known?.let { now -> NetworkCards.fact(now, it) } },
            )
        val ip = network(NetworkFact.LocalIp)
        assertEquals("192.168.1.23", ip?.label)
        assertEquals("IP address", ip?.state, "with state text on, it says what it is")
        assertEquals(Tap.Copy("192.168.1.23"), ip?.tap)
        assertEquals(CellIcon.Symbol("lan"), ip?.icon)
        assertTrue(ip?.readout == true, "its text shows even where labels are off")
        assertEquals(Tap.Copy("203.0.113.7"), network(NetworkFact.PublicIp)?.tap)
        assertEquals("Home 5G", network(NetworkFact.Name)?.label)
        // Not known yet: it says what it would show and opens the IP tool, which says why.
        val unknown = network(NetworkFact.PublicIp, known = null)
        assertEquals("Public IP", unknown?.label)
        assertEquals(Tap.Open(Target.Tool("get-ip")), unknown?.tap)
    }

    @Test
    fun aNetworkSlotKeepsAStableFileFormat() {
        val layout =
            Layout(
                "home",
                LayoutKind.Bar,
                "Home",
                items = listOf(Item.Network("n", NetworkFact.LocalIp)),
            )
        val json = JsonFileStore.json.encodeToString(Layout.serializer(), layout)
        assertTrue("\"type\": \"network\"" in json && "\"shows\": \"local-ip\"" in json, json)
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
