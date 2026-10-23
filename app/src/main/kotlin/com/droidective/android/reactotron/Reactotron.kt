package com.droidective.android.reactotron

import android.content.Context
import android.content.Intent
import com.droidective.core.reactotron.ReactotronCommand
import com.droidective.core.reactotron.ReactotronCommands
import com.droidective.core.reactotron.ReactotronEvent
import com.droidective.core.reactotron.ReactotronServer
import com.droidective.core.reactotron.ReactotronSink
import com.droidective.core.reactotron.ReactotronSnapshot
import com.droidective.core.reactotron.ReactotronSnapshots
import com.droidective.core.reactotron.ReactotronTimeline
import com.droidective.core.reactotron.ReactotronWatch
import com.droidective.core.store.JsonFileStore
import java.io.File
import java.io.IOException
import java.net.BindException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reactotron on the phone: the server a React Native app's Reactotron client connects to, the apps
 * connected to it, and what they said. One for the process; [ReactotronService] keeps it listening
 * while the person has it on.
 */
object Reactotron : ReactotronSink {
    const val TOOL = "reactotron"

    sealed interface State {
        data object Off : State

        data class Listening(val port: Int) : State

        /** It couldn't listen; [taken] when the port is held, by `adb reverse` most likely. */
        data class Failed(val why: String, val taken: Boolean) : State
    }

    /** An app connected now. */
    data class Client(val name: String, val platform: String?, val since: Long)

    val timeline = ReactotronTimeline()

    private val registry = ReactotronCommands()
    private val commands_ = MutableStateFlow<List<ReactotronCommands.Command>>(emptyList())

    /** The custom commands the connected apps offer, to run from here. */
    val commands: StateFlow<List<ReactotronCommands.Command>> = commands_.asStateFlow()

    private val state_ = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = state_.asStateFlow()

    private val clients_ = MutableStateFlow<Map<Int, Client>>(emptyMap())
    val clients: StateFlow<Map<Int, Client>> = clients_.asStateFlow()

    private val changes_ = MutableStateFlow(0L)

    /** Moves on with every entry, for a screen or a notification to catch up. */
    val changes: StateFlow<Long> = changes_.asStateFlow()

    @Volatile private var server: ReactotronServer? = null

    @Volatile private var shelf: ReactotronSnapshots? = null
    @Volatile private var paths: JsonFileStore<List<String>>? = null

    private val watch = ReactotronWatch()
    private val watched_ = MutableStateFlow<List<String>>(emptyList())

    /** The state paths each app sends as they change. */
    val watched: StateFlow<List<String>> = watched_.asStateFlow()

    private val values_ = MutableStateFlow<Map<Int, List<Pair<String, JsonElement>>>>(emptyMap())

    /** What each connection last said the watched paths hold, by connection. */
    val values: StateFlow<Map<Int, List<Pair<String, JsonElement>>>> = values_.asStateFlow()
    private val snapshots_ = MutableStateFlow<List<ReactotronSnapshot>>(emptyList())

    /** The apps' states kept to put back, newest first. */
    val snapshots: StateFlow<List<ReactotronSnapshot>> = snapshots_.asStateFlow()

    /** What came of putting a snapshot back. */
    enum class Restore {
        Sent,
        NotConnected,
        Gone,
    }

    fun start(context: Context) {
        context.startForegroundService(Intent(context, ReactotronService::class.java))
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, ReactotronService::class.java).setAction(ReactotronService.ACTION_STOP)
        )
    }

    /** Starts listening; whether it is, the screen saying why not when it isn't. */
    internal suspend fun listen(): Boolean =
        withContext(Dispatchers.IO) {
            if (server != null) return@withContext true
            val running = ReactotronServer(this@Reactotron)
            try {
                val port = running.start()
                server = running
                state_.value = State.Listening(port)
                true
            } catch (_: BindException) {
                state_.value = State.Failed(TAKEN, taken = true)
                false
            } catch (e: IOException) {
                state_.value = State.Failed(e.message ?: e.javaClass.simpleName, taken = false)
                false
            }
        }

    internal fun shutDown() {
        server?.stop()
        server = null
        clients_.value.keys.forEach {
            registry.gone(it)
            watch.gone(it)
        }
        clients_.value = emptyMap()
        commands_.value = emptyList()
        values_.value = emptyMap()
        if (state_.value is State.Listening) state_.value = State.Off
    }

    /** Runs [command] in the app that offered it, with [args] by name. From any thread. */
    fun run(command: ReactotronCommands.Command, args: Map<String, String>) {
        server?.send(
            command.connection,
            ReactotronCommands.RUN,
            ReactotronCommands.run(command, args),
        )
    }

    /**
     * Reads what is kept in the app's files — the snapshots and the watched paths — the first time;
     * from a background thread.
     */
    fun load(context: Context) {
        if (shelf != null) return
        synchronized(this) {
            if (shelf != null) return
            val kept = JsonFileStore(File(context.filesDir, WATCHED), PATHS) { emptyList() }
            watched_.value = kept.load()
            paths = kept
            val opened = ReactotronSnapshots(File(context.filesDir, SNAPSHOTS))
            snapshots_.value = opened.all()
            shelf = opened
        }
    }

    /**
     * Watches [path] in every connected app, and in each that connects; from a background thread.
     */
    fun watch(path: String) {
        val clean = ReactotronWatch.path(path) ?: return
        if (clean in watched_.value) return
        rewatch(watched_.value + clean)
    }

    fun unwatch(path: String) = rewatch(watched_.value - path)

    private fun rewatch(now: List<String>) {
        watched_.value = now
        paths?.save(now)
        server?.watch(now)
    }

    override fun watched(): List<String> = watched_.value

    /** Asks every connected app for its whole state, which comes back as a snapshot. */
    fun backUp() {
        val request = JsonObject(emptyMap())
        clients_.value.keys.forEach { server?.send(it, ReactotronCommands.BACKUP_REQUEST, request) }
    }

    /**
     * Puts [snapshot] back in its app, through the app's newest connection. From a background
     * thread: the state is read from the files.
     */
    fun restore(snapshot: ReactotronSnapshot): Restore {
        val connection =
            clients_.value.filterValues { it.name == snapshot.app }.keys.maxOrNull()
                ?: return Restore.NotConnected
        val state = shelf?.state(snapshot.id) ?: return Restore.Gone
        val running = server ?: return Restore.NotConnected
        running.send(
            connection,
            ReactotronCommands.RESTORE_REQUEST,
            ReactotronCommands.restore(state),
        )
        return Restore.Sent
    }

    /** The state [snapshot] holds, to show; from a background thread. */
    fun state(snapshot: ReactotronSnapshot): JsonElement? = shelf?.state(snapshot.id)

    fun rename(snapshot: ReactotronSnapshot, name: String) {
        shelf?.let { snapshots_.value = it.rename(snapshot.id, name) }
    }

    fun delete(snapshot: ReactotronSnapshot) {
        shelf?.let { snapshots_.value = it.delete(snapshot.id) }
    }

    /**
     * Asks every connected app for its state at [path] — the value there, or the keys under it —
     * whose answer comes into the timeline. An app needs a state plugin, reactotron-redux or
     * reactotron-mst, to have one to give.
     */
    fun askState(path: String, keys: Boolean) {
        val (type, payload) = ReactotronCommands.stateRequest(path, keys)
        clients_.value.keys.forEach { server?.send(it, type, payload) }
    }

    fun clear() {
        timeline.clear()
        changes_.update { it + 1 }
    }

    override fun connected(connection: Int, clientId: String, intro: ReactotronCommand) {
        val now = System.currentTimeMillis()
        val said = ReactotronEvent.of(intro) as? ReactotronEvent.ClientIntro
        val name = said?.name ?: "App"
        clients_.update { it + (connection to Client(name, said?.platform, now)) }
        timeline.add(now, connection, name, intro, 0)
        changes_.update { it + 1 }
    }

    override fun command(connection: Int, command: ReactotronCommand, size: Int) {
        // Commands come and go as the app's handlers do; they are buttons, not news.
        if (registry.heard(connection, command)) {
            commands_.value = registry.all()
            return
        }
        // Watched values are a panel that changes, not a line per change.
        if (watch.heard(connection, command)) {
            values_.value = watch.all()
            return
        }
        // A backed-up state is a snapshot, kept; one that can't be stays in the timeline.
        val state = ReactotronCommands.backedUp(command)
        if (state != null && keep(connection, state)) return
        timeline.add(System.currentTimeMillis(), connection, name(connection), command, size)
        changes_.update { it + 1 }
    }

    override fun disconnected(connection: Int, why: String?) {
        val gone = clients_.value[connection]
        clients_.update { it - connection }
        registry.gone(connection)
        commands_.value = registry.all()
        watch.gone(connection)
        values_.value = watch.all()
        // A reload taking its own place is no news.
        if (why != null) timeline.gone(System.currentTimeMillis(), connection, gone?.name, why)
        changes_.update { it + 1 }
    }

    private fun name(connection: Int): String? = clients_.value[connection]?.name

    private fun keep(connection: Int, state: JsonElement): Boolean {
        val kept = shelf ?: return false
        val app = name(connection) ?: "App"
        val at = CLOCK.format(LocalTime.now())
        return try {
            snapshots_.value = kept.keep(app, "Snapshot at $at", state, System.currentTimeMillis())
            true
        } catch (_: IOException) {
            false
        }
    }

    private const val SNAPSHOTS = "reactotron-snapshots"
    private const val WATCHED = "reactotron-watched.json"
    private val PATHS = ListSerializer(String.serializer())
    private val CLOCK = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    private const val TAKEN =
        "Something else is listening on port 9090 — a computer's adb reverse, most likely."
}
