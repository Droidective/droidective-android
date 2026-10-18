package com.droidective.core.automations

import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One step of a run: an action, whether it worked, and why not. */
@Serializable data class Step(val description: String, val ok: Boolean, val detail: String? = null)

/**
 * One time an automation fired, for History: what set it off, and each action's result, or why it
 * didn't run at all.
 *
 * @property cause what fired it, such as "When charging" or "Run from the Mac".
 * @property skipped why it didn't run, such as an Only if that didn't hold.
 */
@Serializable
data class RunRecord(
    val automationId: String,
    val name: String,
    val at: Long,
    val cause: String,
    val skipped: String? = null,
    val steps: List<Step> = emptyList(),
) {
    val ok: Boolean
        get() = skipped == null && steps.all { it.ok }
}

/** Saved automations, in `automations.json`. */
class AutomationRepository(root: File) {
    private val store =
        JsonFileStore(File(root, "automations.json"), ListSerializer(Automation.serializer())) {
            emptyList()
        }

    @Synchronized fun all(): List<Automation> = store.load()

    @Synchronized fun get(id: String): Automation? = all().firstOrNull { it.id == id }

    /** Saves [automation] in place of one with its id, or at the end. */
    @Synchronized
    fun save(automation: Automation) {
        require(Automation.idPattern.matches(automation.id)) {
            "\"${automation.id}\" isn’t an automation id"
        }
        val current = all()
        val index = current.indexOfFirst { it.id == automation.id }
        store.save(
            if (index < 0) current + automation
            else current.toMutableList().apply { set(index, automation) }
        )
    }

    @Synchronized fun delete(id: String) = store.save(all().filterNot { it.id == id })
}

/**
 * What the runtime keeps between runs: the toggles running Whiles hold, in `automation-holds.json`,
 * and History, the last [HISTORY_SIZE] runs, in `automation-history.json`.
 */
class AutomationLedger(root: File) {
    private val holdsStore =
        JsonFileStore(File(root, "automation-holds.json"), Holds.serializer()) { Holds() }
    private val historyStore =
        JsonFileStore(
            File(root, "automation-history.json"),
            ListSerializer(RunRecord.serializer()),
        ) {
            emptyList()
        }

    @Synchronized fun holds(): Holds = holdsStore.load()

    @Synchronized fun saveHolds(holds: Holds) = holdsStore.save(holds)

    /** Newest first. */
    @Synchronized fun history(): List<RunRecord> = historyStore.load()

    @Synchronized
    fun record(run: RunRecord) = historyStore.save((listOf(run) + history()).take(HISTORY_SIZE))

    @Synchronized fun clearHistory() = historyStore.save(emptyList())

    companion object {
        const val HISTORY_SIZE = 300
    }
}
