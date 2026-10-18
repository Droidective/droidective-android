package com.droidective.core.automations

import kotlinx.serialization.Serializable

/** One running While automation's claim on a toggle, and the state it set. */
@Serializable data class Holder(val automationId: String, val state: String)

/**
 * Everyone holding one toggle, oldest first, and what it was before the first of them. The newest
 * holder's state is the one in force.
 */
@Serializable
data class HoldStack(val toggleId: String, val original: String?, val holders: List<Holder>)

/**
 * The toggles running While automations hold, kept across restarts so a While that outlives the
 * process still puts things back. Two Whiles can hold one toggle: the newer one wins while both
 * run, the older one's state comes back when the newer ends, and the state from before either comes
 * back when the last one ends. A toggle someone changed by hand in the meantime stays as they set
 * it.
 */
@Serializable
data class Holds(val stacks: List<HoldStack> = emptyList()) {
    /** The holds once [automationId] has started, and the writes that starts it. */
    data class Change(val holds: Holds, val writes: List<Action.SetToggle>)

    fun isHeld(automationId: String): Boolean = stacks.any { stack ->
        stack.holders.any { it.automationId == automationId }
    }

    /**
     * Starts [automationId] setting [sets]. Starting one that already holds changes nothing, so a
     * While noticed again after a restart doesn't undo a change made by hand since.
     *
     * @param current each toggle's state right now, or null when it can't be read.
     */
    fun begin(
        automationId: String,
        sets: List<Action.SetToggle>,
        current: (String) -> String?,
    ): Change {
        if (isHeld(automationId)) return Change(this, emptyList())
        var next = stacks
        val writes = mutableListOf<Action.SetToggle>()
        for (set in sets) {
            val now = current(set.toggleId)
            val holder = Holder(automationId, set.state)
            val existing = next.firstOrNull { it.toggleId == set.toggleId }
            val stack =
                existing?.copy(holders = existing.holders + holder)
                    ?: HoldStack(set.toggleId, original = now, holders = listOf(holder))
            next = next.filterNot { it.toggleId == set.toggleId } + stack
            if (now != set.state) writes += set
        }
        return Change(Holds(next), writes)
    }

    /** Ends [automationId]: the holds after, and the writes that put things back. */
    fun end(automationId: String, current: (String) -> String?): Change {
        val writes = mutableListOf<Action.SetToggle>()
        val next = stacks.mapNotNull { stack -> release(stack, automationId, current, writes) }
        return Change(Holds(next), writes)
    }

    private fun release(
        stack: HoldStack,
        automationId: String,
        current: (String) -> String?,
        writes: MutableList<Action.SetToggle>,
    ): HoldStack? {
        val index = stack.holders.indexOfFirst { it.automationId == automationId }
        if (index < 0) return stack
        val released = stack.holders[index]
        val remaining = stack.holders.filterIndexed { i, _ -> i != index }
        // Someone changed it by hand since: leave it, for this holder and every later one.
        val untouched = current(stack.toggleId) == released.state
        val inForce = index == stack.holders.lastIndex
        val putBack =
            when {
                !untouched || !inForce -> null
                remaining.isNotEmpty() -> remaining.last().state
                else -> stack.original
            }
        if (putBack != null && putBack != released.state) {
            writes += Action.SetToggle(stack.toggleId, putBack)
        }
        return if (remaining.isEmpty()) null else stack.copy(holders = remaining)
    }
}
