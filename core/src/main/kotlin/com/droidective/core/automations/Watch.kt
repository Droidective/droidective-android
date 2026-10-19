package com.droidective.core.automations

import kotlinx.serialization.Serializable

/** A watched state that started or stopped holding. [initial] is the first reading. */
data class StateChange(val state: State, val holds: Boolean, val initial: Boolean = false)

/** One watched state's last settled reading, and a change waiting to settle, if there is one. */
@Serializable
data class WatchEntry(
    val state: State,
    val settled: Boolean,
    val pendingHolds: Boolean? = null,
    val pendingSince: Long = 0,
)

/** A [StateWatch] as a file, since the process can die between one broadcast and the next. */
@Serializable data class WatchSnapshot(val entries: List<WatchEntry> = emptyList())

/**
 * Turns readings of the watched states into changes. A change to a state with a settle time counts
 * only once it has held that long; flapping back first cancels it.
 */
class StateWatch(snapshot: WatchSnapshot = WatchSnapshot()) {
    private data class Pending(val holds: Boolean, val since: Long)

    private val settled = mutableMapOf<State, Boolean>()
    private val pending = mutableMapOf<State, Pending>()

    init {
        snapshot.entries.forEach { entry ->
            settled[entry.state] = entry.settled
            entry.pendingHolds?.let { pending[entry.state] = Pending(it, entry.pendingSince) }
        }
    }

    fun snapshot(): WatchSnapshot =
        WatchSnapshot(
            settled.map { (state, value) ->
                WatchEntry(state, value, pending[state]?.holds, pending[state]?.since ?: 0)
            }
        )

    /** The state's current reading; returns the change it makes, if one counts yet. */
    fun observe(state: State, holds: Boolean, now: Long): StateChange? {
        val known = settled[state]
        if (known == null) {
            settled[state] = holds
            return StateChange(state, holds, initial = true)
        }
        if (holds == known) {
            pending.remove(state)
            return null
        }
        if (state.settleMillis == 0L) {
            settled[state] = holds
            return StateChange(state, holds)
        }
        if (pending[state]?.holds != holds) pending[state] = Pending(holds, now)
        return null
    }

    /** Changes that have now held for their settle time. */
    fun due(now: Long): List<StateChange> {
        val ready = pending.filter { (state, p) -> now - p.since >= state.settleMillis }
        ready.forEach { (state, p) ->
            pending.remove(state)
            settled[state] = p.holds
        }
        return ready.map { (state, p) -> StateChange(state, p.holds) }
    }

    /** When the next pending change becomes due, for the timer that calls [due]. */
    fun nextDue(): Long? = pending.minOfOrNull { (state, p) -> p.since + state.settleMillis }

    /** Stops watching states no automation needs any more. */
    fun retain(states: Set<State>) {
        settled.keys.retainAll(states)
        pending.keys.retainAll(states)
    }
}

/** What a change or an event sets off. */
sealed interface Firing {
    val automation: Automation

    /** A When automation runs its actions once. */
    data class Run(override val automation: Automation) : Firing

    /** A While automation starts holding its toggles. */
    data class Begin(override val automation: Automation) : Firing

    /** A While automation ends and puts its toggles back. */
    data class Finish(override val automation: Automation) : Firing
}

object Matcher {
    /** The states enabled automations trigger on, which the runtime has to watch. */
    fun watched(automations: List<Automation>): Set<State> =
        automations
            .filter { it.enabled }
            .mapNotNull { stateOf(it.trigger) }
            .filter { it.wakes != null }
            .toSet()

    /** Whether any enabled automation needs the automation service to be running. */
    fun needsService(automations: List<Automation>): Boolean = automations.any {
        it.enabled && it.trigger.wakes == Wake.Service
    }

    /**
     * What [change] sets off. A first reading starts a While that already holds, but isn't a start
     * or an end: the service starting while the phone charges isn't "charging started".
     */
    fun onChange(automations: List<Automation>, change: StateChange): List<Firing> =
        automations
            .filter { it.enabled && stateOf(it.trigger) == change.state }
            .mapNotNull { automation -> firing(automation, change) }

    /** The enabled automations [event] triggers. Manual and schedules run by id instead. */
    fun onEvent(automations: List<Automation>, event: Event): List<Firing> =
        automations
            .filter { it.enabled }
            .filter { automation ->
                val wanted = (automation.trigger as? Trigger.On)?.event
                wanted != null && matches(wanted, event)
            }
            .map { Firing.Run(it) }

    private fun firing(automation: Automation, change: StateChange): Firing? =
        when (automation.trigger) {
            is Trigger.While ->
                when {
                    change.holds -> Firing.Begin(automation)
                    change.initial -> null
                    else -> Firing.Finish(automation)
                }
            is Trigger.Starts -> Firing.Run(automation).takeIf { change.holds && !change.initial }
            is Trigger.Ends -> Firing.Run(automation).takeIf { !change.holds && !change.initial }
            is Trigger.On -> null
        }

    private fun matches(wanted: Event, happened: Event): Boolean =
        when (wanted) {
            Event.Manual,
            is Event.Schedule -> false
            is Event.AppInstalled ->
                happened is Event.AppInstalled &&
                    (wanted.packageName == null || wanted.packageName == happened.packageName)
            is Event.AppCrashed ->
                happened is Event.AppCrashed &&
                    (wanted.packageName == null || wanted.packageName == happened.packageName)
            Event.Restarted,
            Event.Unlocked -> wanted == happened
        }

    private fun stateOf(trigger: Trigger): State? =
        when (trigger) {
            is Trigger.Starts -> trigger.state
            is Trigger.Ends -> trigger.state
            is Trigger.While -> trigger.state
            is Trigger.On -> null
        }
}
