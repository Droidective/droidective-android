package com.droidective.android.surface

import android.content.Context
import android.os.Build
import android.widget.Toast
import com.droidective.android.automation.ActionTaps
import com.droidective.android.automation.Automations
import com.droidective.android.droidective
import com.droidective.android.ui.copy
import com.droidective.core.layout.Tap
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.Toggles

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
 * A slot's tap that runs in the background: from a home-screen widget, a tile or the notification
 * bar. Every surface goes through here, so a slot does the same thing wherever it's placed, and a
 * tap that needs a person ends in a notification ([Problems]).
 */
object BackgroundTaps {
    const val CAUSE = "From a widget, tile or notification bar"

    /** Runs [tap]; an [Tap.Open] is the caller's, since only it can start the activity. */
    suspend fun run(context: Context, tap: Tap) {
        when (tap) {
            is Tap.RunToggle -> {
                val toggle = Toggles.byId(tap.toggleId) ?: return
                val outcome = ToggleTaps.advance(context, toggle)
                if (outcome !is Outcome.Applied) Problems.report(context, toggle, outcome)
            }
            is Tap.RunCommand -> {
                val command = context.droidective.commands.get(tap.commandId) ?: return
                Problems.reportCommand(context, command, CommandTaps.run(context, command))
            }
            is Tap.RunAutomation -> Automations.runNow(context, tap.automationId, CAUSE)
            is Tap.RunAction -> {
                val step = ActionTaps.run(context, tap.action, tap.label)
                if (!step.ok) Problems.reportStep(context, tap.label, step.detail)
            }
            is Tap.Copy -> Copies.copy(context, tap.text)
            is Tap.Open -> Unit
        }
    }
}
