package com.droidective.android.reactotron

import android.content.Context
import android.content.Intent
import com.droidective.core.reactotron.ReactotronCommand
import com.droidective.core.reactotron.ReactotronEvent
import com.droidective.core.reactotron.ReactotronServer
import com.droidective.core.reactotron.ReactotronSink
import com.droidective.core.reactotron.ReactotronTimeline
import java.io.IOException
import java.net.BindException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

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

    private val state_ = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = state_.asStateFlow()

    private val clients_ = MutableStateFlow<Map<Int, Client>>(emptyMap())
    val clients: StateFlow<Map<Int, Client>> = clients_.asStateFlow()

    private val changes_ = MutableStateFlow(0L)

    /** Moves on with every entry, for a screen or a notification to catch up. */
    val changes: StateFlow<Long> = changes_.asStateFlow()

    @Volatile private var server: ReactotronServer? = null

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
        clients_.value = emptyMap()
        if (state_.value is State.Listening) state_.value = State.Off
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
        timeline.add(System.currentTimeMillis(), connection, name(connection), command, size)
        changes_.update { it + 1 }
    }

    override fun disconnected(connection: Int, why: String?) {
        val gone = clients_.value[connection]
        clients_.update { it - connection }
        // A reload taking its own place is no news.
        if (why != null) timeline.gone(System.currentTimeMillis(), connection, gone?.name, why)
        changes_.update { it + 1 }
    }

    private fun name(connection: Int): String? = clients_.value[connection]?.name

    private const val TAKEN =
        "Something else is listening on port 9090 — a computer's adb reverse, most likely."
}
