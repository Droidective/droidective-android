package com.droidective.core.automations

import com.droidective.core.AccessLevel
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parts the runtime leans on: holds, watching states, matching and History. */
class AutomationRuntimeTest {
    private val dir = createTempDirectory("automations").toFile()

    @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

    private val phone = mutableMapOf("http-proxy" to "off", "show-touches" to "off")

    private fun set(id: String, state: String) = Action.SetToggle(id, state)

    private fun apply(change: Holds.Change): Holds {
        change.writes.forEach { phone[it.toggleId] = it.state }
        return change.holds
    }

    @Test
    fun aWhilePutsItsTogglesBack() {
        var holds = Holds()
        holds = apply(holds.begin("desk", listOf(set("http-proxy", "on")), phone::get))
        assertEquals("on", phone["http-proxy"])
        assertTrue(holds.isHeld("desk"))
        holds = apply(holds.end("desk", phone::get))
        assertEquals("off", phone["http-proxy"])
        assertEquals(Holds(), holds)
    }

    @Test
    fun aToggleChangedByHandStaysThatWay() {
        var holds = apply(Holds().begin("desk", listOf(set("http-proxy", "on")), phone::get))
        phone["http-proxy"] = "off" // the person turned it off themselves
        phone["show-touches"] = "on"
        val end = holds.end("desk", phone::get)
        assertEquals(emptyList(), end.writes)
        holds = apply(end)
        assertEquals(Holds(), holds)
    }

    @Test
    fun overlappingWhilesHandBackInOrder() {
        phone["http-proxy"] = "off"
        var holds = apply(Holds().begin("office", listOf(set("http-proxy", "on")), phone::get))
        holds =
            apply(
                holds.begin(
                    "debug",
                    listOf(set("http-proxy", "on"), set("show-touches", "on")),
                    phone::get,
                )
            )
        // The newer one ends first: the proxy is still the older one's, so it stays on.
        val debugEnds = holds.end("debug", phone::get)
        assertEquals(listOf(set("show-touches", "off")), debugEnds.writes)
        holds = apply(debugEnds)
        holds = apply(holds.end("office", phone::get))
        assertEquals("off", phone["http-proxy"])
        assertEquals(Holds(), holds)
    }

    @Test
    fun theOlderWhileEndingFirstLeavesTheNewerOnesState() {
        val proxy = Action.SetToggle("private-dns", "hostname")
        phone["private-dns"] = "automatic"
        var holds = apply(Holds().begin("old", listOf(set("private-dns", "off")), phone::get))
        holds = apply(holds.begin("new", listOf(proxy), phone::get))
        val oldEnds = holds.end("old", phone::get)
        assertEquals(emptyList(), oldEnds.writes, "the newer one's state is in force")
        holds = apply(oldEnds)
        holds = apply(holds.end("new", phone::get))
        assertEquals("automatic", phone["private-dns"], "back to before either")
    }

    @Test
    fun anOlderWhileEndingNeverOverwritesAHandChange() {
        phone["private-dns"] = "automatic"
        var holds = apply(Holds().begin("old", listOf(set("private-dns", "off")), phone::get))
        holds = apply(holds.begin("new", listOf(set("private-dns", "hostname")), phone::get))
        phone["private-dns"] = "off" // by hand, to what the older one happened to set
        val oldEnds = holds.end("old", phone::get)
        assertEquals(emptyList(), oldEnds.writes, "the newer one wasn't asked to take over")
        holds = apply(oldEnds)
        assertEquals(emptyList(), holds.end("new", phone::get).writes)
    }

    @Test
    fun startingTwiceChangesNothingAndUnknownBeforeIsLeftAlone() {
        var holds = apply(Holds().begin("desk", listOf(set("http-proxy", "on")), phone::get))
        phone["http-proxy"] = "off"
        assertEquals(
            emptyList(),
            holds.begin("desk", listOf(set("http-proxy", "on")), phone::get).writes,
        )
        holds = apply(Holds().begin("blind", listOf(set("show-touches", "on"))) { null })
        assertEquals(emptyList(), holds.end("blind", phone::get).writes, "nothing to go back to")
    }

    @Test
    fun aChangeCountsOnceItHasHeld() {
        val watch = StateWatch()
        val car = State.BluetoothConnected("Car audio")
        assertEquals(StateChange(car, false, initial = true), watch.observe(car, false, now = 0))
        assertNull(watch.observe(car, true, now = 1_000))
        assertEquals(11_000, watch.nextDue())
        assertNull(watch.observe(car, false, now = 2_000), "flapped back")
        assertNull(watch.nextDue())
        watch.observe(car, true, now = 3_000)
        assertEquals(emptyList(), watch.due(now = 12_999))
        assertEquals(listOf(StateChange(car, true)), watch.due(now = 13_000))
        assertNull(watch.observe(car, true, now = 14_000))
    }

    @Test
    fun aWatchPicksUpWhereTheLastProcessLeftOff() {
        val car = State.BluetoothConnected("Car audio")
        val first = StateWatch()
        first.observe(car, false, now = 0)
        first.observe(car, true, now = 1_000) // pending, then the process dies
        val ledger = AutomationLedger(dir)
        ledger.saveWatch(first.snapshot())
        val second = StateWatch(ledger.watch())
        assertEquals(11_000, second.nextDue())
        assertNull(second.observe(car, true, now = 5_000), "still settling")
        assertEquals(listOf(StateChange(car, true)), second.due(now = 11_000))
        assertEquals(WatchSnapshot(listOf(WatchEntry(car, settled = true))), second.snapshot())
    }

    @Test
    fun aStateWithoutASettleTimeChangesAtOnce() {
        val watch = StateWatch()
        val app = State.AppOpen("com.example.app")
        watch.observe(app, false, now = 0)
        assertEquals(StateChange(app, true), watch.observe(app, true, now = 1))
    }

    @Test
    fun changesStartRunAndEndTheRightAutomations() {
        val wifi = State.OnWifi("Office-5G")
        val whileDesk =
            Automation(
                "desk",
                "Desk",
                trigger = Trigger.While(wifi),
                actions = listOf(set("http-proxy", "on")),
            )
        val arrive =
            Automation(
                "arrive",
                "Arrive",
                trigger = Trigger.Starts(wifi),
                actions = listOf(Action.Wait(1)),
            )
        val leave =
            Automation(
                "leave",
                "Leave",
                trigger = Trigger.Ends(wifi),
                actions = listOf(Action.Wait(1)),
            )
        val off = arrive.copy(id = "off", enabled = false)
        val all = listOf(whileDesk, arrive, leave, off)
        assertEquals(listOf(wifi), Matcher.watched(all).toList())
        assertEquals(
            listOf(Firing.Begin(whileDesk)),
            Matcher.onChange(all, StateChange(wifi, true, initial = true)),
            "already at the desk when watching starts",
        )
        assertEquals(emptyList(), Matcher.onChange(all, StateChange(wifi, false, initial = true)))
        assertEquals(
            listOf(Firing.Begin(whileDesk), Firing.Run(arrive)),
            Matcher.onChange(all, StateChange(wifi, true)),
        )
        assertEquals(
            listOf(Firing.Finish(whileDesk), Firing.Run(leave)),
            Matcher.onChange(all, StateChange(wifi, false)),
        )
    }

    @Test
    fun crashesMatchTheirAppOrAny() {
        val anyCrash =
            Automation(
                "any",
                "Any",
                trigger = Trigger.On(Event.AppCrashed()),
                actions = listOf(Action.Wait(1)),
            )
        val oneCrash =
            anyCrash.copy(id = "one", trigger = Trigger.On(Event.AppCrashed("com.example.app")))
        val install = anyCrash.copy(id = "install", trigger = Trigger.On(Event.AppInstalled()))
        val all = listOf(anyCrash, oneCrash, install)
        assertEquals(
            listOf(anyCrash, oneCrash),
            Matcher.onEvent(all, Event.AppCrashed("com.example.app")).map { it.automation },
        )
        assertEquals(
            listOf(anyCrash),
            Matcher.onEvent(all, Event.AppCrashed("com.other")).map { it.automation },
        )
        assertTrue(Matcher.needsService(listOf(oneCrash)), "only a running listener hears crashes")
        assertEquals(AccessLevel.Granted, Event.AppCrashed().level)
    }

    @Test
    fun eventsMatchByKind() {
        val anyInstall =
            Automation(
                "any",
                "Any",
                trigger = Trigger.On(Event.AppInstalled()),
                actions = listOf(Action.Wait(1)),
            )
        val oneInstall =
            anyInstall.copy(id = "one", trigger = Trigger.On(Event.AppInstalled("com.example.app")))
        val boot = anyInstall.copy(id = "boot", trigger = Trigger.On(Event.Restarted))
        val manual = anyInstall.copy(id = "manual", trigger = Trigger.On(Event.Manual))
        val all = listOf(anyInstall, oneInstall, boot, manual)
        assertEquals(
            listOf(anyInstall, oneInstall),
            Matcher.onEvent(all, Event.AppInstalled("com.example.app")).map { it.automation },
        )
        assertEquals(
            listOf(anyInstall),
            Matcher.onEvent(all, Event.AppInstalled("com.other")).map { it.automation },
        )
        assertEquals(listOf(boot), Matcher.onEvent(all, Event.Restarted).map { it.automation })
        assertEquals(
            emptyList(),
            Matcher.onEvent(all, Event.AppCrashed("com.example.app")),
            "a crash isn't an install",
        )
        assertEquals(emptyList(), Matcher.onEvent(all, Event.Manual), "manual runs by id")
        assertTrue(Matcher.needsService(all))
        assertTrue(!Matcher.needsService(listOf(boot, manual)))
    }

    @Test
    fun aChangeAnAutomationMadeCarriesItsChain() {
        val writes = RecentWrites()
        val chain = Cause(listOf("a", "b"))
        writes.record("wifi", chain, at = 1_000)
        val wifiOff = StateChange(State.ToggleIs("wifi", "off"), holds = true)
        assertEquals(chain, writes.causeOf(wifiOff, at = 2_000))
        assertEquals(
            Cause(),
            writes.causeOf(wifiOff, at = 1_000 + RecentWrites.WINDOW_MILLIS + 1),
            "by hand, later",
        )
        assertEquals(Cause(), writes.causeOf(StateChange(State.Charging, true), at = 2_000))
        // Two automations setting each other's triggers: the chain grows until it stops.
        var cause = Cause()
        repeat(10) { i ->
            val id = if (i % 2 == 0) "a" else "b"
            if (!cause.allows(id)) return@repeat
            writes.record("wifi", cause.then(id), at = 3_000L + i)
            cause = writes.causeOf(wifiOff, at = 3_001L + i)
        }
        assertTrue(cause.chain.size <= Cause.MAX_CHAIN)
        assertTrue(!cause.allows("a") || !cause.allows("b"), "the loop stopped")
    }

    @Test
    fun historyKeepsTheNewestRuns() {
        val ledger = AutomationLedger(dir)
        repeat(AutomationLedger.HISTORY_SIZE + 5) { i ->
            ledger.record(RunRecord("a", "A", at = i.toLong(), cause = "When you run it"))
        }
        val history = ledger.history()
        assertEquals(AutomationLedger.HISTORY_SIZE, history.size)
        assertEquals(AutomationLedger.HISTORY_SIZE + 4L, history.first().at)
        val failed =
            RunRecord("a", "A", 0, "x", steps = listOf(Step("Wi-Fi: Off", ok = false, "No shell")))
        assertTrue(!failed.ok)
        assertTrue(!RunRecord("a", "A", 0, "x", skipped = "Only if charging").ok)
    }

    @Test
    fun holdsAndAutomationsSurviveARestart() {
        val holds = Holds().begin("desk", listOf(set("http-proxy", "on")), phone::get).holds
        AutomationLedger(dir).saveHolds(holds)
        assertEquals(holds, AutomationLedger(dir).holds())
        val repo = AutomationRepository(dir)
        val a =
            Automation(
                "a",
                "A",
                trigger = Trigger.On(Event.Manual),
                actions = listOf(Action.Wait(1)),
            )
        repo.save(a)
        repo.save(a.copy(id = "b"))
        repo.save(a.copy(name = "A again"))
        assertEquals(listOf("A again", "A"), repo.all().map { it.name }, "saved in place")
        repo.delete("a")
        assertEquals(listOf("b"), repo.all().map { it.id })
    }
}
