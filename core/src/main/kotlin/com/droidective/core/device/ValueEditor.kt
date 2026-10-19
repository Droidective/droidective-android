package com.droidective.core.device

import com.droidective.core.shell.Parse
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A value someone changed here, with what it held before, so it can be put back. */
@Serializable
data class ValueChange(
    val table: ValueTable,
    val key: String,
    val before: String?,
    val after: String?,
    val at: Long,
)

/**
 * Every change made in the Settings Editor, newest first, kept in `value-changes.json` so a change
 * can still be undone after the app restarts: a wrong setting can stop the phone working as
 * expected, and the old value is the way back.
 */
class ValueChanges(root: File) {
    private val store =
        JsonFileStore(File(root, "value-changes.json"), ListSerializer(ValueChange.serializer())) {
            emptyList()
        }

    @Synchronized fun all(): List<ValueChange> = store.load()

    /**
     * Records [change]. A second change to the same key keeps the first one's [before], and one
     * that puts the key back where it started leaves nothing to undo, so it's forgotten.
     */
    @Synchronized
    fun record(change: ValueChange) {
        val earlier = all().firstOrNull { it.table == change.table && it.key == change.key }
        val kept = if (earlier == null) change else change.copy(before = earlier.before)
        val rest = all().filterNot { it.table == change.table && it.key == change.key }
        store.save((listOfNotNull(kept.takeIf { it.before != it.after }) + rest).take(LIMIT))
    }

    /** Forgets [change], once it's been put back. */
    @Synchronized
    fun forget(change: ValueChange) {
        store.save(all().filterNot { it.table == change.table && it.key == change.key })
    }

    private companion object {
        const val LIMIT = 200
    }
}

/** How a change or an undo went. */
sealed interface ValueOutcome {
    /** It took; [change] is what the phone holds now, and is on record. */
    data class Changed(val change: ValueChange) : ValueOutcome

    /** The old value is back, and the change is off the record. */
    data object Undone : ValueOutcome

    /** It already held that. */
    data object Unchanged : ValueOutcome

    /** The command ran, but the phone still holds [value]. */
    data class Kept(val value: String?) : ValueOutcome

    /** It couldn't be done, and [reason] is why. */
    data class Refused(val reason: String) : ValueOutcome
}

/**
 * Changes values and puts them back, recording each change in [changes]. Every change reads the
 * value before and after: `settings put` succeeds whatever Android does with the value, so the
 * read-back is the only way to know it took.
 */
class ValueEditor(
    private val shell: Shell,
    private val changes: ValueChanges,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Sets [key] in [table] to [value], or deletes it for null. */
    suspend fun set(table: ValueTable, key: String, value: String?): ValueOutcome {
        if (!SystemValues.editable(table, key)) {
            return ValueOutcome.Refused("Read-only (ro.) properties can’t be changed.")
        }
        if (value == null && !table.deletable) {
            return ValueOutcome.Refused("${table.title} can't be deleted.")
        }
        return try {
            val before =
                read(table, key).getOrElse {
                    return refused(it)
                }
            val write =
                if (value == null) SystemValues.delete(table, key)
                else SystemValues.put(table, key, value)
            run(write)?.let {
                return ValueOutcome.Refused(SystemValues.explain(table, it))
            }
            val after =
                read(table, key).getOrElse {
                    return refused(it)
                }
            when {
                after == before && after == value -> ValueOutcome.Unchanged
                after == before -> ValueOutcome.Kept(after)
                else -> {
                    val change = ValueChange(table, key, before, after, now())
                    changes.record(change)
                    ValueOutcome.Changed(change)
                }
            }
        } catch (e: ShellUnavailableException) {
            ValueOutcome.Refused(e.message ?: "The shell stopped responding.")
        }
    }

    /** Puts [change] back the way it was before it. */
    suspend fun undo(change: ValueChange): ValueOutcome =
        try {
            run(SystemValues.undo(change))?.let {
                return ValueOutcome.Refused(it)
            }
            val now =
                read(change.table, change.key).getOrElse {
                    return refused(it)
                }
            if (now == change.before) {
                changes.forget(change)
                ValueOutcome.Undone
            } else {
                ValueOutcome.Kept(now)
            }
        } catch (e: ShellUnavailableException) {
            ValueOutcome.Refused(e.message ?: "The shell stopped responding.")
        }

    private suspend fun read(table: ValueTable, key: String): Result<String?> {
        val result = shell.run(SystemValues.get(table, key))
        return if (result.succeeded) Result.success(SystemValues.current(table, result.stdout))
        else Result.failure(IllegalStateException(Parse.failure(result.stderr, result.stdout)))
    }

    /** Null when [command] worked, else what it said. */
    private suspend fun run(command: ShellCommand): String? {
        val result = shell.run(command)
        return if (result.succeeded) null
        else Parse.failure(result.stderr, result.stdout) ?: "It exited with ${result.exitCode}."
    }

    private fun refused(error: Throwable) =
        ValueOutcome.Refused(error.message ?: "The value couldn't be read.")
}
