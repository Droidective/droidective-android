package com.droidective.android.automation

import android.content.Context
import com.droidective.android.droidective
import com.droidective.android.surface.Surfaces
import com.droidective.android.surface.ToggleTaps
import com.droidective.core.automations.Action
import com.droidective.core.automations.Automation
import com.droidective.core.automations.AutomationRules
import com.droidective.core.automations.Cause
import com.droidective.core.automations.Conditions
import com.droidective.core.automations.RunRecord
import com.droidective.core.automations.Step
import com.droidective.core.toggles.Toggles
import com.droidective.platform.StateReader
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs one firing: checks it may run, runs its actions or starts or ends its While, and records it
 * in History. Problems that need a person become notifications, since this runs in the background.
 */
internal class AutomationRunner(private val context: Context) {
    private val app = context.droidective
    private val actions = AutomationActions(context)
    private val reader =
        StateReader(
            context,
            shell = { app.access.shell() },
            toggleState = { id -> Toggles.byId(id)?.let { ToggleTaps.stateOf(context, it) } },
        )

    /** The run, as History records it; null when there was nothing to record. */
    suspend fun fire(request: RunRequest): RunRecord? {
        app.access.settled()
        val automation = app.automations.get(request.automationId) ?: return null
        val skipped = skipReason(automation, request)
        val steps =
            if (skipped != null) emptyList()
            else
                when (request.kind) {
                    RunRequest.Kind.Run -> run(automation, request)
                    RunRequest.Kind.Begin -> begin(automation) ?: return null
                    RunRequest.Kind.Finish -> finish(automation) ?: return null
                }
        if (steps.isNotEmpty()) Surfaces.refresh(context)
        val record =
            RunRecord(
                automation.id,
                automation.name,
                System.currentTimeMillis(),
                request.cause,
                skipped,
                steps,
            )
        app.ledger.record(record)
        return record
    }

    private suspend fun skipReason(automation: Automation, request: RunRequest): String? {
        if (!request.chain.allows(automation.id)) {
            return if (automation.id in request.chain.chain) "It was already running in this chain"
            else "The chain of automations was already five long"
        }
        // A While that's ending still puts its toggles back, switched off or not.
        if (request.kind == RunRequest.Kind.Finish) return null
        if (!automation.enabled) return "It’s switched off"
        AutomationRules.problems(automation).firstOrNull()?.let {
            return it
        }
        if (request.startAt > 0) return null
        val facts = automation.conditions.associate { it.state to reader.holds(it.state) }
        return Conditions.firstUnmet(automation.conditions, facts::get)?.let {
            "${it.describe()}, and it didn’t"
        }
    }

    private suspend fun run(automation: Automation, request: RunRequest): List<Step> {
        val steps = mutableListOf<Step>()
        for (index in request.startAt until automation.actions.size) {
            val action = automation.actions[index]
            if (action is Action.Wait) {
                val at = System.currentTimeMillis() + action.seconds * 1_000L
                Automations.enqueue(
                    context,
                    request.copy(startAt = index + 1, cause = "${request.cause}, after a wait"),
                    delayMillis = action.seconds * 1_000L,
                )
                val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(at))
                steps += Step("Wait ${action.seconds} s", ok = true, "Carries on at $time")
                break
            }
            steps += actions.run(action, automation, request.chain)
        }
        return steps
    }

    /** Starts a While; null when it already holds, which happens when it's noticed again. */
    private suspend fun begin(automation: Automation): List<Step>? = holding.withLock {
        val holds = app.ledger.holds()
        if (holds.isHeld(automation.id)) return@withLock null
        val sets = automation.actions.filterIsInstance<Action.SetToggle>()
        val now = read(sets.map { it.toggleId })
        val change = holds.begin(automation.id, sets, now::get)
        app.ledger.saveHolds(change.holds)
        change.writes
            .map { actions.run(it, automation, Cause()) }
            .ifEmpty { listOf(Step("Everything was already set", ok = true)) }
    }

    /** Ends a While and puts its toggles back; null when it wasn't holding anything. */
    private suspend fun finish(automation: Automation): List<Step>? = holding.withLock {
        val holds = app.ledger.holds()
        if (!holds.isHeld(automation.id)) return@withLock null
        val held =
            holds.stacks
                .filter { stack -> stack.holders.any { it.automationId == automation.id } }
                .map { it.toggleId }
        val change = holds.end(automation.id, read(held)::get)
        app.ledger.saveHolds(change.holds)
        change.writes
            .map { actions.run(it, automation, Cause()) }
            .ifEmpty { listOf(Step("Nothing to put back: it was changed since", ok = true)) }
    }

    /** Each toggle's state now, read before the holds change, since reading can take a moment. */
    private suspend fun read(toggleIds: List<String>): Map<String, String?> =
        toggleIds.associateWith { id ->
            Toggles.byId(id)?.let { ToggleTaps.stateOf(context, it) }
        }

    private companion object {
        /** One While starts or ends at a time, since they share the holds. */
        val holding = Mutex()
    }
}
