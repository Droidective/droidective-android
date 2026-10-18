package com.droidective.core.automations

import com.droidective.core.apps.AppActions
import com.droidective.core.commands.DeepLinkRepository
import com.droidective.core.toggles.Probe
import com.droidective.core.toggles.ToggleDef
import com.droidective.core.toggles.Toggles
import java.time.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Why an automation can't be switched on, in the order to fix them. Empty means it's fine. The
 * editor and import both check here, so neither can save an automation the runtime would refuse.
 */
object AutomationRules {
    const val MAX_WAIT_SECONDS = 3_600
    private const val MINUTES_PER_DAY = 1_440

    fun problems(
        automation: Automation,
        toggles: (String) -> ToggleDef? = Toggles::byId,
    ): List<String> = buildList {
        if (!Automation.idPattern.matches(automation.id)) add("“${automation.id}” isn’t an id")
        if (automation.name.isBlank()) add("It needs a name")
        if (automation.actions.isEmpty()) add("It needs at least one action")
        addAll(triggerProblems(automation.trigger, toggles))
        automation.conditions.forEach { addAll(stateProblems(it.state, toggles)) }
        automation.actions.forEach { addAll(actionProblems(it, automation.id, toggles)) }
        if (automation.isWhile) addAll(whileProblems(automation.actions, toggles))
        if (triggersItself(automation)) {
            add("It sets the toggle that triggers it, so it would keep triggering itself")
        }
    }

    private fun triggerProblems(trigger: Trigger, toggles: (String) -> ToggleDef?): List<String> =
        when (trigger) {
            is Trigger.On -> eventProblems(trigger.event)
            is Trigger.Starts -> canTrigger(trigger.state) + stateProblems(trigger.state, toggles)
            is Trigger.Ends -> canTrigger(trigger.state) + stateProblems(trigger.state, toggles)
            is Trigger.While -> canTrigger(trigger.state) + stateProblems(trigger.state, toggles)
        }

    private fun canTrigger(state: State): List<String> =
        if (state.wakes == null) {
            listOf("“${state.describe(holds = true)}” can be an Only if, but not a trigger")
        } else {
            emptyList()
        }

    private fun eventProblems(event: Event): List<String> =
        when (event) {
            is Event.Schedule -> timeProblems(event.minuteOfDay) + dayProblems(event.days)
            is Event.AppInstalled -> event.packageName?.let(::packageProblems).orEmpty()
            Event.Manual,
            Event.Restarted,
            Event.Unlocked -> emptyList()
        }

    private fun stateProblems(state: State, toggles: (String) -> ToggleDef?): List<String> =
        when (state) {
            is State.OnWifi -> blankProblems(state.network, "network name")
            is State.BluetoothConnected -> blankProblems(state.device, "device name")
            is State.AppOpen -> packageProblems(state.packageName)
            is State.BatteryBelow -> percentProblems(state.percent)
            is State.BatteryAbove -> percentProblems(state.percent)
            is State.ToggleIs ->
                toggleStateProblems(state.toggleId, state.state, toggles, read = true)
            is State.TimeBetween -> windowProblems(state)
            State.Charging,
            State.ScreenOn,
            State.Headphones,
            State.ShellAvailable -> emptyList()
        }

    private fun actionProblems(
        action: Action,
        selfId: String,
        toggles: (String) -> ToggleDef?,
    ): List<String> =
        when (action) {
            is Action.SetToggle ->
                toggleStateProblems(action.toggleId, action.state, toggles, read = false)
            is Action.OpenApp -> packageProblems(action.packageName)
            is Action.OpenLink ->
                if (DeepLinkRepository.isLink(action.uri)) emptyList()
                else listOf("“${action.uri}” isn’t a link")
            is Action.OpenShortcut -> blankProblems(action.intentUri, "shortcut")
            is Action.OpenSettings -> blankProblems(action.settingsAction, "Settings screen")
            is Action.RunCommand -> blankProblems(action.commandId, "command")
            is Action.ForApp -> packageProblems(action.packageName)
            is Action.Permission -> permissionProblems(action)
            is Action.Notify -> notifyProblems(action, selfId, toggles)
            is Action.Wait -> waitProblems(action.seconds)
            is Action.RunAutomation ->
                if (action.automationId == selfId) listOf("It can’t run itself") else emptyList()
        }

    private fun notifyProblems(
        notify: Action.Notify,
        selfId: String,
        toggles: (String) -> ToggleDef?,
    ): List<String> = buildList {
        if (notify.title.isBlank()) add("A notification needs a title")
        notify.buttons.forEach { button ->
            if (button.label.isBlank()) add("A notification button needs a label")
            if (!button.action.canBeAButton) {
                add("A notification button can open something or run an automation")
            }
            addAll(actionProblems(button.action, selfId, toggles))
        }
    }

    /** A While puts every toggle back when it ends, so each must be one it can read. */
    private fun whileProblems(actions: List<Action>, toggles: (String) -> ToggleDef?) =
        actions
            .filterNot { action ->
                action is Action.SetToggle &&
                    toggles(action.toggleId)?.let { it.read != Probe.None && !it.isAction } == true
            }
            .map { "A While can only set toggles it can read back, so it can put them back" }
            .distinct()

    private fun triggersItself(automation: Automation): Boolean {
        val watched =
            when (val trigger = automation.trigger) {
                is Trigger.Starts -> trigger.state
                is Trigger.Ends -> trigger.state
                is Trigger.While -> trigger.state
                is Trigger.On -> null
            }
                as? State.ToggleIs ?: return false
        return automation.actions.any { it is Action.SetToggle && it.toggleId == watched.toggleId }
    }

    private fun toggleStateProblems(
        toggleId: String,
        state: String,
        toggles: (String) -> ToggleDef?,
        read: Boolean,
    ): List<String> {
        val toggle = toggles(toggleId) ?: return listOf("There’s no toggle “$toggleId”")
        return buildList {
            if (!toggle.isBuilt) add("${toggle.title} isn’t built yet")
            if (read && toggle.read == Probe.None) add("${toggle.title} can’t be read")
            if (toggle.states.none { it.id == state }) add("${toggle.title} has no “$state”")
        }
    }

    private fun permissionProblems(action: Action.Permission): List<String> =
        packageProblems(action.packageName) +
            if (AppActions.isPermissionName(action.permission)) emptyList()
            else listOf("“${action.permission}” isn’t a permission name")

    private fun packageProblems(packageName: String) =
        if (AppActions.isPackageName(packageName)) emptyList()
        else listOf("“$packageName” isn’t a package name")

    private fun blankProblems(value: String?, what: String) =
        if (value != null && value.isBlank()) listOf("The $what is empty") else emptyList()

    private fun timeProblems(minute: Int) =
        if (minute in 0 until MINUTES_PER_DAY) emptyList() else listOf("$minute isn’t a time")

    private fun dayProblems(days: Set<Day>) =
        if (days.isEmpty()) listOf("Pick at least one day") else emptyList()

    private fun windowProblems(window: State.TimeBetween) =
        timeProblems(window.startMinute) +
            timeProblems(window.endMinute) +
            dayProblems(window.days) +
            if (window.startMinute == window.endMinute) listOf("The window starts as it ends")
            else emptyList()

    private fun percentProblems(percent: Int) =
        if (percent in 1..99) emptyList() else listOf("$percent% isn’t between 1% and 99%")

    private fun waitProblems(seconds: Int) =
        if (seconds in 1..MAX_WAIT_SECONDS) emptyList()
        else listOf("A wait is from 1 second to an hour")
}

/** Opening something or running an automation; what a notification button can do. */
val Action.canBeAButton: Boolean
    get() =
        this is Action.OpenApp ||
            this is Action.OpenLink ||
            this is Action.OpenShortcut ||
            this is Action.OpenSettings ||
            this is Action.RunAutomation

/** Clear data, uninstall and revoke: they confirm when added, and the card marks them. */
val Action.isDestructive: Boolean
    get() = (this is Action.ForApp && action.destructive) || (this is Action.Permission && !grant)

/**
 * Which automations led to this run. A chain stops after [MAX_CHAIN] and never runs an automation
 * twice, so two automations that set each other's triggers can't loop.
 */
@Serializable
data class Cause(val chain: List<String> = emptyList()) {
    fun allows(automationId: String): Boolean = automationId !in chain && chain.size < MAX_CHAIN

    fun then(automationId: String): Cause = Cause(chain + automationId)

    companion object {
        const val MAX_CHAIN = 5
    }
}

object Conditions {
    /**
     * The first condition that doesn't hold, or null when they all do. One that can't be read right
     * now counts as not holding: an automation shouldn't run on a guess.
     */
    fun firstUnmet(conditions: List<Condition>, holds: (State) -> Boolean?): Condition? =
        conditions.firstOrNull { condition ->
            val value = holds(condition.state) ?: return@firstOrNull true
            value == condition.negate
        }
}

/** Times of day, kept pure so the alarms the app sets can be tested. */
object Clock {
    /** A week and a day: every set of days repeats within it. */
    private const val DAYS_TO_TRY = 8

    fun State.TimeBetween.holdsAt(at: LocalDateTime): Boolean = holdsAt(dayOf(at), minuteOf(at))

    /** Whether the window is open at [minute] on [day]; past midnight, it's the start day's. */
    fun State.TimeBetween.holdsAt(day: Day, minute: Int): Boolean =
        if (startMinute < endMinute) {
            day in days && minute in startMinute until endMinute
        } else {
            (day in days && minute >= startMinute) || (previous(day) in days && minute < endMinute)
        }

    /** The next time [schedule] fires after [from], to the minute. */
    fun next(schedule: Event.Schedule, from: LocalDateTime): LocalDateTime {
        var candidate = from.withSecond(0).withNano(0).plusMinutes(1)
        repeat(DAYS_TO_TRY) {
            if (dayOf(candidate) in schedule.days && minuteOf(candidate) == schedule.minuteOfDay) {
                return candidate
            }
            candidate = nextMinuteOf(candidate, schedule.minuteOfDay)
        }
        error("${schedule.describe()} never fires") // AutomationRules refuses an empty day set.
    }

    /** The next time [window] opens or closes after [from], for the alarm that watches it. */
    fun nextChange(window: State.TimeBetween, from: LocalDateTime): LocalDateTime {
        val now = window.holdsAt(dayOf(from), minuteOf(from))
        var candidate = from.withSecond(0).withNano(0).plusMinutes(1)
        repeat(2 * DAYS_TO_TRY) {
            if (window.holdsAt(dayOf(candidate), minuteOf(candidate)) != now) return candidate
            candidate =
                minOf(
                    nextMinuteOf(candidate, window.startMinute),
                    nextMinuteOf(candidate, window.endMinute),
                )
        }
        error("${window.describe(true)} never changes") // AutomationRules refuses that.
    }

    private fun nextMinuteOf(after: LocalDateTime, minuteOfDay: Int): LocalDateTime {
        val sameDay = after.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        return if (sameDay.isAfter(after)) sameDay else sameDay.plusDays(1)
    }

    private fun dayOf(at: LocalDateTime) = Day.ofIso(at.dayOfWeek.value)

    private fun minuteOf(at: LocalDateTime) = at.hour * 60 + at.minute

    private fun previous(day: Day) = Day.ofIso((day.iso + 5) % 7 + 1)
}
