package com.droidective.android.ui

import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.ToggleDef

/** What to tell someone after a tap. Null means the toggle's new state says it all. */
object OutcomeText {
    fun describe(toggle: ToggleDef, outcome: Outcome): String? =
        when (outcome) {
            is Outcome.Applied -> if (toggle.isAction) "${toggle.title}: done" else null
            is Outcome.UseFallback -> null
            is Outcome.NeedsLevel ->
                "${toggle.title} needs ${outcome.level.title} access. Setup shows how to get it."
            is Outcome.NeedsSpecialAccess ->
                "Allow ${outcome.access.title} for Droidective, then try again."
            is Outcome.DidNotStick ->
                "Android kept ${toggle.title} at ${label(toggle, outcome.actual)}."
            is Outcome.Failed -> "${toggle.title} didn’t change: ${outcome.message}"
        }

    fun label(toggle: ToggleDef, state: String?): String =
        toggle.states.firstOrNull { it.id == state }?.label ?: "an unknown state"
}
