package com.droidective.android.surface

import android.content.Context
import android.os.Build
import android.widget.Toast
import com.droidective.android.automation.ActionTaps
import com.droidective.android.automation.Automations
import com.droidective.android.droidective
import com.droidective.android.ui.OutcomeText
import com.droidective.android.ui.copy
import com.droidective.core.layout.Tap
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.TapNotes
import com.droidective.core.toggles.ToggleDef
import com.droidective.core.toggles.Toggles
import com.droidective.platform.AdbPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copying what a slot shows, such as an IP address, from wherever it's tapped. */
object Copies {
    fun copy(context: Context, text: String) {
        context.copy("Copied from Droidective", text)
        // Android 13 and later confirm a copy on their own.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * A slot's tap that runs in the background: from a home-screen widget, a tile, the notification bar
 * or the floating controls. Every surface goes through here, so a slot does the same thing wherever
 * it's placed. Each tap ends in a toast saying how it went, and a tap that needs a person also in a
 * notification that does the job ([Problems]).
 */
object BackgroundTaps {
    const val CAUSE = "From a widget, tile or notification bar"

    /** Runs [tap]; an [Tap.Open] is the caller's, since only it can start the activity. */
    suspend fun run(context: Context, tap: Tap) {
        val said =
            when (tap) {
                is Tap.RunToggle -> toggle(context, tap.toggleId)
                is Tap.RunCommand -> command(context, tap.commandId)
                is Tap.RunAutomation -> {
                    Automations.runNow(context, tap.automationId, CAUSE)
                    context.droidective.automations.get(tap.automationId)?.let {
                        Said("${it.name}: running")
                    }
                }
                is Tap.RunAction -> {
                    val step = ActionTaps.run(context, tap.action, tap.label)
                    if (step.ok) Said("${tap.label}: done")
                    else Said(Problems.reportStep(context, tap.label, step.detail), long = true)
                }
                is Tap.Copy -> {
                    Copies.copy(context, tap.text)
                    null
                }
                is Tap.Open -> null
            }
        said?.let { show(context, it) }
    }

    private suspend fun toggle(context: Context, toggleId: String): Said? {
        val toggle = Toggles.byId(toggleId) ?: return null
        val outcome = ToggleTaps.advance(context, toggle)
        if (outcome !is Outcome.Applied) {
            val problem =
                OutcomeText.describe(toggle, outcome)
                    ?: "${toggle.title} needs Shell, which isn’t running."
            Problems.report(context, toggle, outcome)
            return Said(problem, long = true)
        }
        val note = TapNotes.applied(toggle, outcome.state) ?: return null
        if (!TapNotes.addsAdbAddress(toggle, outcome.state)) return Said(note)
        return Said(withAdbAddress(context, toggle, note), long = true)
    }

    /** Wireless debugging's note with where to connect, or what kept the app from finding out. */
    private suspend fun withAdbAddress(context: Context, toggle: ToggleDef, note: String): String {
        if (!AdbPort.allowed(context)) {
            Problems.reportLocalNetwork(context)
            return "$note. To see its port, allow local network access from the notification."
        }
        AdbPort.address(context)?.let {
            return "$note · $it"
        }
        // Off Wi-Fi, Android turns it back off a moment after the write read back as on.
        if (context.droidective.toggles.read(toggle) == "off") {
            Surfaces.refresh(context)
            return "Android turned ${toggle.title} off again: it works only on Wi-Fi."
        }
        return "$note. Its port didn’t show up."
    }

    private suspend fun command(context: Context, commandId: String): Said? {
        val command = context.droidective.commands.get(commandId) ?: return null
        val problem = Problems.reportCommand(context, command, CommandTaps.run(context, command))
        return if (problem == null) Said("${command.name}: done") else Said(problem, long = true)
    }

    /**
     * A toast works from the background, over whatever app is open, but only from the main thread.
     */
    private suspend fun show(context: Context, said: Said) =
        withContext(Dispatchers.Main) {
            val length = if (said.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context.applicationContext, said.text, length).show()
        }

    /** What a tap says: briefly when it worked, for longer when there's more to read. */
    private data class Said(val text: String, val long: Boolean = false)
}
