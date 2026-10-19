package com.droidective.android.surface

import android.content.Context
import com.droidective.android.automation.ActionTaps
import com.droidective.android.automation.Automations
import com.droidective.android.droidective
import com.droidective.core.layout.Tap
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.Toggles

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
            is Tap.Open -> Unit
        }
    }
}
