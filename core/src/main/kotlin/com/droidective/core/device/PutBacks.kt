package com.droidective.core.device

import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A change the app makes only for a while, and how to undo it: show taps and demo mode during a
 * recording, a phone forced into Doze.
 *
 * @property what the change in a person's words.
 * @property lines shell lines run after the toggles, in order.
 * @property toggles toggle ids and the state each goes back to, set first and through the app's own
 *   toggles, so a toggle the app keeps state for (demo mode) reads right afterwards.
 * @property heldAt when [PutBacks.hold] noted it, in milliseconds since the epoch.
 */
@Serializable
data class PutBack(
    val id: String,
    val what: String,
    val lines: List<String>,
    val toggles: Map<String, String> = emptyMap(),
    val heldAt: Long = 0,
)

/** Sets a toggle to a state, as the app's toggles do: how a [PutBack]'s toggles go back. */
fun interface SetToggle {
    suspend fun set(toggleId: String, state: String)
}

/**
 * Every [PutBack] still owed, kept on disk from just before its change until it's undone. A change
 * the app didn't live to undo (swiped away, force stopped, killed) is undone the next time it
 * starts ([putBackAll]), or just before the same change is made again ([clear]), so what a change
 * reads as "before" is never the app's own leftover.
 */
class PutBacks(
    private val store: JsonFileStore<List<PutBack>>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Kept in [root] as `put-backs.json`. */
    constructor(
        root: File
    ) : this(
        JsonFileStore(File(root, "put-backs.json"), ListSerializer(PutBack.serializer())) {
            emptyList()
        }
    )

    private val lock = Mutex()

    /** Owed now, oldest first. */
    fun owed(): List<PutBack> = store.load()

    /** Undoes what's left under [id], if anything: call it before making that change again. */
    suspend fun clear(id: String, shell: Shell, toggles: SetToggle) = lock.withLock {
        store.load().filter { it.id == id }.forEach { undo(it, shell, toggles) }
    }

    /** Notes how to undo [putBack]: call it before making the change. */
    suspend fun hold(putBack: PutBack) = lock.withLock {
        store.save(store.load().filterNot { it.id == putBack.id } + putBack.copy(heldAt = clock()))
    }

    /** The change [id] was undone the usual way. */
    suspend fun release(id: String) = lock.withLock {
        store.save(store.load().filterNot { it.id == id })
    }

    /**
     * Undoes everything held before [before], and returns what was: a new process passes the time
     * it started, so a change it has already made itself stays. What the shell couldn't reach stays
     * owed.
     */
    suspend fun putBackAll(shell: Shell, toggles: SetToggle, before: Long): List<PutBack> =
        lock.withLock {
            store.load().filter { it.heldAt < before && undo(it, shell, toggles) }
        }

    /** Undoes [putBack], then forgets it; keeps it, for the next try, if the shell went. */
    private suspend fun undo(putBack: PutBack, shell: Shell, toggles: SetToggle): Boolean {
        try {
            putBack.toggles.forEach { (id, state) -> toggles.set(id, state) }
            putBack.lines.forEach { shell.run(ShellCommand(it)) }
        } catch (_: ShellUnavailableException) {
            return false
        }
        store.save(store.load().filterNot { it == putBack })
        return true
    }
}
