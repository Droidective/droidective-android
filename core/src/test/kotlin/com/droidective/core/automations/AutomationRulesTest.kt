package com.droidective.core.automations

import com.droidective.core.AccessLevel
import com.droidective.core.apps.AppAction
import com.droidective.core.automations.Clock.holdsAt
import com.droidective.core.location.Coordinates
import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class AutomationRulesTest {
    private fun automation(
        trigger: Trigger = Trigger.On(Event.Manual),
        actions: List<Action> = listOf(Action.SetToggle("wifi", "off")),
        conditions: List<Condition> = emptyList(),
        id: String = "proxy-at-desk",
    ) =
        Automation(
            id,
            "Proxy at my desk",
            trigger = trigger,
            conditions = conditions,
            actions = actions,
        )

    /** One of every state, so a new kind can't skip the checks below without failing here. */
    private val everyState: List<State> =
        listOf(
                State.OnWifi("Office-5G"),
                State.BluetoothConnected("Car audio"),
                State.AppOpen("com.example.app.debug"),
                State.Charging,
                State.ScreenOn,
                State.Headphones,
                State.BatteryBelow(20),
                State.BatteryAbove(80),
                State.ToggleIs("wifi", "on"),
                State.TimeBetween(22 * 60, 7 * 60),
                State.ShellAvailable,
            )
            .onEach { state ->
                // Exhaustive: adding a State means adding it to this list.
                when (state) {
                    is State.OnWifi,
                    is State.BluetoothConnected,
                    is State.AppOpen,
                    State.Charging,
                    State.ScreenOn,
                    State.Headphones,
                    is State.BatteryBelow,
                    is State.BatteryAbove,
                    is State.ToggleIs,
                    is State.TimeBetween,
                    State.ShellAvailable -> Unit
                }
            }

    @Test
    fun theFileFormatIsStable() {
        val desk =
            automation(
                trigger = Trigger.While(State.OnWifi("Office-5G")),
                conditions = listOf(Condition(State.ShellAvailable, negate = true)),
                actions = listOf(Action.SetToggle("http-proxy", "on")),
            )
        // Exactly what the store writes: the file is the format.
        val dir = createTempDirectory("format").toFile()
        AutomationRepository(dir).save(desk)
        val written = Json.parseToJsonElement(File(dir, "automations.json").readText())
        dir.deleteRecursively()
        assertEquals(
            Json.parseToJsonElement(
                """[{"id":"proxy-at-desk","name":"Proxy at my desk","enabled":true,""" +
                    """"trigger":{"type":"while","state":{"type":"wifi","network":"Office-5G"}},""" +
                    """"conditions":[{"state":{"type":"shell"},"negate":true}],""" +
                    """"actions":[{"type":"set-toggle","toggleId":"http-proxy","state":"on"}]}]"""
            ),
            written,
        )
    }

    @Test
    fun everyKindRoundTrips() {
        val actions =
            listOf(
                Action.SetToggle("wifi", "off"),
                Action.OpenApp("com.google.android.apps.maps", "Maps"),
                Action.OpenLink("myapp://onboarding"),
                Action.OpenShortcut("Chat", "intent:#Intent;end"),
                Action.OpenSettings("Wi-Fi", "android.settings.WIFI_SETTINGS"),
                Action.RunCommand("cmd-1"),
                Action.ForApp("com.example.app", AppAction.ClearData),
                Action.Permission("com.example.app", "android.permission.CAMERA", grant = false),
                Action.Notify(
                    "Start Shizuku",
                    "Shell is off",
                    listOf(Button("Open", Action.OpenLink("a://b"))),
                ),
                Action.Wait(5),
                Action.RunAutomation("other"),
                Action.SetLocation("London", Coordinates(51.5074, -0.1278)),
                Action.RealLocation,
                Action.ReactotronCommand("StreamLab", "resetStore", "Reset store"),
                Action.ReactotronCommand("StreamLab", "login", "Log in", mapOf("user" to "ada")),
                Action.ReactotronRestore("4f1c", "Two in the cart", "StreamLab"),
            )
        val triggers =
            everyState.flatMap { listOf(Trigger.Starts(it), Trigger.Ends(it), Trigger.While(it)) } +
                listOf(
                    Trigger.On(Event.Manual),
                    Trigger.On(Event.Schedule(23 * 60 + 30, setOf(Day.Monday, Day.Friday))),
                    Trigger.On(Event.Restarted),
                    Trigger.On(Event.AppInstalled("com.example.app")),
                    Trigger.On(Event.AppCrashed("com.example.app")),
                    Trigger.On(Event.AppCrashed()),
                    Trigger.On(Event.Unlocked),
                )
        triggers.forEach { trigger ->
            val original = automation(trigger = trigger, actions = actions)
            val text = Json.encodeToString(Automation.serializer(), original)
            assertEquals(original, Json.decodeFromString(Automation.serializer(), text))
        }
    }

    @Test
    fun toolActionsKeepTheirFormatAndSayWhatTheyDo() {
        val actions =
            listOf(
                Action.SetLocation("London", Coordinates(51.5074, -0.1278)),
                Action.RealLocation,
                Action.ReactotronCommand("StreamLab", "login", "Log in", mapOf("user" to "ada")),
                Action.ReactotronRestore("4f1c", "Two in the cart", "StreamLab"),
            )
        assertEquals(
            Json.parseToJsonElement(
                """[{"type":"set-location","name":"London",""" +
                    """"where":{"latitude":51.5074,"longitude":-0.1278}},""" +
                    """{"type":"real-location"},""" +
                    """{"type":"reactotron-command","app":"StreamLab","command":"login",""" +
                    """"label":"Log in","args":{"user":"ada"}},""" +
                    """{"type":"reactotron-restore","snapshotId":"4f1c",""" +
                    """"name":"Two in the cart","app":"StreamLab"}]"""
            ),
            Json.encodeToJsonElement(ListSerializer(Action.serializer()), actions),
        )
        assertEquals(
            listOf(
                "Put the phone at London",
                "Give the phone its own location back",
                "Run “Log in” in StreamLab",
                "Put “Two in the cart” back in StreamLab",
            ),
            actions.map { Review.describe(it, commands = { null }) },
        )
        // No shell, nothing to undo, and the app alone runs them.
        assertEquals(emptyList(), actions.flatMap { Review.shell(it, commands = { null }) })
        actions.forEach { action ->
            assertEquals(AccessLevel.App, action.level(), "$action")
            assertFalse(action.isDestructive, "$action")
        }
    }

    @Test
    fun aGoodAutomationHasNoProblems() {
        assertEquals(emptyList(), AutomationRules.problems(automation()))
        assertEquals(
            emptyList(),
            AutomationRules.problems(
                automation(
                    trigger = Trigger.While(State.AppOpen("com.example.app.debug")),
                    actions =
                        listOf(
                            Action.SetToggle("show-touches", "on"),
                            Action.SetToggle("animation-scale", "0.5"),
                            Action.SetToggle("stay-awake", "on"),
                        ),
                )
            ),
        )
    }

    @Test
    fun everyStateThatCanTriggerSaysWhatWakesTheApp() {
        everyState.forEach { state ->
            val problems =
                AutomationRules.problems(
                    automation(trigger = Trigger.Starts(state), actions = listOf(Action.Wait(1)))
                )
            if (state.wakes == null) assertTrue(problems.any { "not a trigger" in it }, "$state")
            else assertEquals(emptyList(), problems, "$state")
        }
    }

    @Test
    fun aWhileOnlySetsToggleItCanReadBack() {
        val message = "A While can only set toggles it can read back, so it can put them back"
        listOf(
                Action.SetToggle("sync-now", "run"), // an action: nothing to read back
                Action.OpenApp("com.example.app", "Example"),
            )
            .forEach { action ->
                val problems =
                    AutomationRules.problems(
                        automation(
                            trigger = Trigger.While(State.Charging),
                            actions = listOf(action),
                        )
                    )
                assertTrue(message in problems, "$action: $problems")
            }
    }

    @Test
    fun itCantTriggerOrRunItself() {
        val loop =
            automation(
                trigger = Trigger.Starts(State.ToggleIs("wifi", "off")),
                actions = listOf(Action.SetToggle("wifi", "on")),
            )
        assertTrue(AutomationRules.problems(loop).any { "triggering itself" in it })
        val runsItself = automation(actions = listOf(Action.RunAutomation("proxy-at-desk")))
        assertEquals(listOf("It can’t run itself"), AutomationRules.problems(runsItself))
    }

    @Test
    fun badValuesAreNamed() {
        val problems =
            AutomationRules.problems(
                automation(
                    id = "Bad Id",
                    trigger = Trigger.On(Event.Schedule(1_440, emptySet())),
                    conditions = listOf(Condition(State.BatteryBelow(0))),
                    actions =
                        listOf(
                            Action.SetToggle("wifi", "maybe"),
                            Action.SetToggle("no-such-toggle", "on"),
                            Action.OpenLink("not a link"),
                            Action.ForApp("com.x'; reboot; '", AppAction.ForceStop),
                            Action.Wait(0),
                            Action.Notify("Hi", buttons = listOf(Button("Stop", Action.Wait(1)))),
                            Action.SetLocation(" ", Coordinates(0.0, 0.0)),
                            Action.ReactotronCommand("", "resetStore", "Reset store"),
                            Action.ReactotronCommand("StreamLab", "", "Reset store"),
                            Action.ReactotronRestore(" ", "Two in the cart", "StreamLab"),
                        ),
                )
            )
        listOf(
                "isn’t an id",
                "isn’t a time",
                "Pick at least one day",
                "isn’t between 1% and 99%",
                "has no “maybe”",
                "There’s no toggle “no-such-toggle”",
                "isn’t a link",
                "isn’t a package name",
                "A wait is from 1 second to an hour",
                "A notification button can open something or run an automation",
                "The place name is empty",
                "The app name is empty",
                "The command is empty",
                "The snapshot is empty",
            )
            .forEach { expected -> assertTrue(problems.any { expected in it }, expected) }
    }

    @Test
    fun aCrashTriggerNamesARealPackageOrNone() {
        assertEquals(
            listOf("“not a package” isn’t a package name"),
            AutomationRules.problems(
                automation(trigger = Trigger.On(Event.AppCrashed("not a package")))
            ),
        )
        assertEquals(
            emptyList(),
            AutomationRules.problems(automation(trigger = Trigger.On(Event.AppCrashed()))),
        )
    }

    @Test
    fun destructiveActionsAreMarked() {
        assertTrue(Action.ForApp("com.example.app", AppAction.ClearData).isDestructive)
        assertTrue(Action.ForApp("com.example.app", AppAction.Uninstall).isDestructive)
        assertTrue(
            Action.Permission("com.example.app", "android.permission.CAMERA", false).isDestructive
        )
        assertFalse(Action.ForApp("com.example.app", AppAction.ForceStop).isDestructive)
        assertFalse(
            Action.Permission("com.example.app", "android.permission.CAMERA", true).isDestructive
        )
    }

    @Test
    fun aChainStopsAtFiveAndNeverRepeats() {
        var cause = Cause()
        listOf("a", "b", "c", "d", "e").forEach { id ->
            assertTrue(cause.allows(id), id)
            cause = cause.then(id)
        }
        assertFalse(cause.allows("f"), "a sixth link")
        assertFalse(Cause(listOf("a", "b")).allows("a"), "a loop")
    }

    @Test
    fun conditionsMustAllHoldAndUnknownMeansNo() {
        val charging = Condition(State.Charging)
        val noShell = Condition(State.ShellAvailable, negate = true)
        val facts = mapOf<State, Boolean?>(State.Charging to true, State.ShellAvailable to false)
        assertNull(Conditions.firstUnmet(listOf(charging, noShell), facts::get))
        assertEquals(
            noShell,
            Conditions.firstUnmet(listOf(charging, noShell)) { s ->
                s == State.ShellAvailable || facts[s] == true
            },
        )
        assertEquals(charging, Conditions.firstUnmet(listOf(charging)) { null })
        assertEquals("Only if Shell isn’t available", noShell.describe())
    }

    @Test
    fun aTimeWindowPastMidnightBelongsToTheDayItStarts() {
        val night = State.TimeBetween(22 * 60, 7 * 60, setOf(Day.Friday))
        assertTrue(night.holdsAt(Day.Friday, 23 * 60))
        assertTrue(night.holdsAt(Day.Saturday, 6 * 60 + 59), "Friday night, into Saturday")
        assertFalse(night.holdsAt(Day.Saturday, 7 * 60), "the end is outside")
        assertFalse(night.holdsAt(Day.Saturday, 23 * 60), "Saturday isn't in the set")
        assertFalse(night.holdsAt(Day.Friday, 6 * 60), "Thursday night isn't either")
        val work = State.TimeBetween(9 * 60, 17 * 60, setOf(Day.Monday))
        assertTrue(work.holdsAt(Day.Monday, 9 * 60))
        assertFalse(work.holdsAt(Day.Monday, 17 * 60))
    }

    @Test
    fun schedulesAndWindowsFindTheirNextMinute() {
        // Monday 19 October 2026, 23:29:30.
        val now = LocalDateTime.of(2026, 10, 19, 23, 29, 30)
        val bedtime = Event.Schedule(23 * 60 + 30)
        assertEquals(LocalDateTime.of(2026, 10, 19, 23, 30), Clock.next(bedtime, now))
        assertEquals(
            LocalDateTime.of(2026, 10, 20, 23, 30),
            Clock.next(bedtime, LocalDateTime.of(2026, 10, 19, 23, 30)),
            "not again in the same minute",
        )
        val fridays = Event.Schedule(8 * 60, setOf(Day.Friday))
        assertEquals(LocalDateTime.of(2026, 10, 23, 8, 0), Clock.next(fridays, now))
        val night = State.TimeBetween(22 * 60, 7 * 60, setOf(Day.Monday))
        assertEquals(LocalDateTime.of(2026, 10, 20, 7, 0), Clock.nextChange(night, now))
        assertEquals(
            LocalDateTime.of(2026, 10, 26, 22, 0),
            Clock.nextChange(night, LocalDateTime.of(2026, 10, 20, 7, 0)),
            "next Monday",
        )
    }

    @Test
    fun descriptionsReadLikeTheDesign() {
        assertEquals("Every day at 23:30", Event.Schedule(23 * 60 + 30).describe())
        assertEquals(
            "Weekdays at 08:05",
            Event.Schedule(8 * 60 + 5, Day.entries.take(5).toSet()).describe(),
        )
        assertEquals(
            "While on the Wi-Fi network “Office-5G”",
            Trigger.While(State.OnWifi("Office-5G")).describe(),
        )
        assertEquals(
            "When “Car audio” is connected",
            Trigger.Starts(State.BluetoothConnected("Car audio")).describe(),
        )
        assertEquals(
            "When com.example.app closes",
            Trigger.Ends(State.AppOpen("com.example.app")).describe(),
        )
    }
}
