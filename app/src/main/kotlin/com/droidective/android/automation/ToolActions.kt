package com.droidective.android.automation

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import com.droidective.android.location.MockLocation
import com.droidective.android.reactotron.Reactotron
import com.droidective.android.surface.ShellRuns
import com.droidective.core.apps.ShellStarts
import com.droidective.core.automations.Action
import com.droidective.core.automations.Step
import com.droidective.core.reactotron.ReactotronCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The actions that reach into a tool, Mock Location or Reactotron, without opening it. */
internal object ToolActions {
    /**
     * Android refuses a foreground service to an app off screen unless a widget, tile, notification
     * or alarm started it, which leaves out triggers such as joining a Wi-Fi network. The shell may
     * allow one for a few seconds, so with Shell it is asked once the first start is refused.
     */
    suspend fun setLocation(
        context: Context,
        action: Action.SetLocation,
        description: String,
    ): Step {
        if (!MockLocation.chosen(context)) {
            return Step(description, ok = false, MockLocation.NOT_CHOSEN)
        }
        if (started(context, action)) return Step(description, ok = true)
        val allow =
            ShellRuns.execute(
                context,
                listOf(ShellStarts.allowForegroundService(context.packageName)),
            )
        if (allow is ShellRuns.Result.Ran && allow.result.succeeded && started(context, action)) {
            return Step(description, ok = true)
        }
        return Step(
            description,
            ok = false,
            "Android doesn’t let an app start Mock Location from the background, except from a " +
                "widget, tile, notification or schedule. With Shell, Droidective can.",
        )
    }

    private fun started(context: Context, action: Action.SetLocation): Boolean =
        try {
            MockLocation.start(context, action.where, action.name)
            true
        } catch (e: IllegalStateException) {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    e !is ForegroundServiceStartNotAllowedException
            ) {
                throw e
            }
            false
        }

    /** Nothing to give back when Mock Location isn't standing in, which is already the outcome. */
    fun realLocation(context: Context, description: String): Step {
        if (MockLocation.state.value is MockLocation.State.On) MockLocation.stop(context)
        return Step(description, ok = true)
    }

    /** Sent to the app; what its handler does with it is the app's to say, in its timeline. */
    fun reactotronCommand(action: Action.ReactotronCommand, description: String): Step {
        if (Reactotron.state.value !is Reactotron.State.Listening) {
            return Step(description, ok = false, notListening(action.app))
        }
        val clients = Reactotron.clients.value
        val command =
            ReactotronCommands.find(
                Reactotron.commands.value,
                { clients[it]?.name },
                action.app,
                action.command,
            )
        if (command == null) {
            val why =
                if (clients.values.none { it.name == action.app }) {
                    "${action.app} isn’t connected to Reactotron."
                } else {
                    "${action.app} doesn’t offer “${action.label}” now."
                }
            return Step(description, ok = false, why)
        }
        Reactotron.run(command, action.args)
        return Step(description, ok = true)
    }

    /** Sent to the app's store plugin, which replaces the app's state with the snapshot's. */
    suspend fun reactotronRestore(
        context: Context,
        action: Action.ReactotronRestore,
        description: String,
    ): Step {
        if (Reactotron.state.value !is Reactotron.State.Listening) {
            return Step(description, ok = false, notListening(action.app))
        }
        val gone = "The snapshot “${action.name}” is no longer on this phone."
        val restored =
            withContext(Dispatchers.IO) {
                Reactotron.loadSnapshots(context)
                Reactotron.snapshots.value
                    .firstOrNull { it.id == action.snapshotId }
                    ?.let(Reactotron::restore)
            } ?: return Step(description, ok = false, gone)
        return when (restored) {
            Reactotron.Restore.Sent -> Step(description, ok = true)
            Reactotron.Restore.NotConnected ->
                Step(description, ok = false, "${action.app} isn’t connected to Reactotron.")
            Reactotron.Restore.Gone -> Step(description, ok = false, gone)
        }
    }

    private fun notListening(app: String) =
        "Reactotron isn’t on. Start it in Droidective, then open $app."
}
