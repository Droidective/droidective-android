package com.droidective.android.surface

import android.content.Context
import androidx.core.content.edit
import com.droidective.android.droidective
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.Probe
import com.droidective.core.toggles.ToggleDef

/**
 * The one path every surface's tap takes: read, move to the next state, remember it, redraw
 * everything. Presenting a problem is the caller's job, because a foreground screen can open a
 * panel and a widget in the background can't.
 */
object ToggleTaps {
    private const val PREFS = "last-states"

    /** The toggle's state: read from the phone, or the last one set when it can't be read. */
    suspend fun stateOf(context: Context, toggle: ToggleDef): String? =
        context.droidective.toggles.read(toggle)
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(toggle.id, null)

    suspend fun advance(context: Context, toggle: ToggleDef): Outcome {
        context.droidective.access.settled()
        return set(context, toggle, toggle.next(stateOf(context, toggle)).id)
    }

    /**
     * Sets [toggle] to [state] directly, for screens that offer every state at once and for
     * automations, which set several and redraw once at the end, so they pass [refresh] false.
     */
    suspend fun set(
        context: Context,
        toggle: ToggleDef,
        state: String,
        refresh: Boolean = true,
    ): Outcome {
        val app = context.droidective
        app.access.settled()
        val outcome = app.toggles.apply(toggle, state)
        if (outcome is Outcome.Applied && !toggle.isAction && toggle.read is Probe.None) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(toggle.id, outcome.state)
            }
        }
        if (refresh) Surfaces.refresh(context)
        return outcome
    }
}
