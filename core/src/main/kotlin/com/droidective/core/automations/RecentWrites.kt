package com.droidective.core.automations

/**
 * The toggles automations just set, and the chain that set each. When the automation service sees
 * one of those toggles change, the automations that change sets off carry that chain, so two
 * automations that set each other's triggers stop after five like any other chain instead of
 * looping forever.
 *
 * @param windowMillis how long after a write a change still counts as the write's doing.
 */
class RecentWrites(private val windowMillis: Long = WINDOW_MILLIS) {
    private data class Write(val chain: Cause, val at: Long)

    private val writes = mutableMapOf<String, Write>()

    /** [chain] (ending with the automation that ran) set [toggleId] at [at]. */
    @Synchronized
    fun record(toggleId: String, chain: Cause, at: Long) {
        writes[toggleId] = Write(chain, at)
    }

    /**
     * The chain behind [change]: the last write to its toggle, if that's recent enough, or a fresh
     * chain for a change someone made by hand.
     */
    @Synchronized
    fun causeOf(change: StateChange, at: Long): Cause {
        val toggleId = (change.state as? State.ToggleIs)?.toggleId ?: return Cause()
        val write = writes[toggleId] ?: return Cause()
        return if (at - write.at <= windowMillis) write.chain else Cause()
    }

    companion object {
        const val WINDOW_MILLIS = 15_000L
    }
}
