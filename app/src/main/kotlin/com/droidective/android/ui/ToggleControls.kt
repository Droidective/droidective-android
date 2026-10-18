package com.droidective.android.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.surface.Launch
import com.droidective.android.surface.ShellRuns
import com.droidective.android.surface.Surfaces
import com.droidective.android.surface.ToggleTaps
import com.droidective.core.layout.Target
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.ToggleDef
import com.droidective.core.toggles.Write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The live state of [toggles], re-read when access changes and whenever a widget, tile or the
 * notification bar changes something while the screen is open.
 */
@Composable
fun rememberToggleStates(toggles: List<ToggleDef>): SnapshotStateMap<String, String> {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val states = remember { mutableStateMapOf<String, String>() }
    suspend fun readAll() {
        toggles
            .filter { it.isBuilt }
            .forEach { toggle ->
                ToggleTaps.stateOf(context, toggle)?.let { states[toggle.id] = it }
            }
    }
    LaunchedEffect(access, toggles) { readAll() }
    LaunchedEffect(toggles) { Surfaces.changes.collect { readAll() } }
    return states
}

/**
 * A tap on a toggle in any of the app's screens: open its screen, or move it on and say why not.
 *
 * @param state the state to set, or null to move to the next one.
 */
fun CoroutineScope.tapToggle(
    context: Context,
    toggle: ToggleDef,
    snackbar: SnackbarHostState,
    onOpenSetup: () -> Unit,
    state: String? = null,
) {
    launch {
        val write = toggle.write
        if (write is Write.Api && write.api.opensScreen) {
            Launch.intentFor(context, Target.ToggleScreen(toggle.id))?.let(context::openSafely)
            return@launch
        }
        val (outcome, text) = runInForeground(context, toggle, state)
        val message = text ?: return@launch
        val result =
            snackbar.showSnackbar(
                message,
                actionLabel = if (outcome is Outcome.NeedsLevel) "Set up" else null,
            )
        if (result == SnackbarResult.ActionPerformed) onOpenSetup()
    }
}

/** What to tell someone after running a custom command from the app. */
object CommandOutput {
    fun describe(name: String, result: ShellRuns.Result): String =
        when (result) {
            ShellRuns.Result.NoShell -> "$name needs Shell. Setup shows how to get it."
            is ShellRuns.Result.Failed -> "$name didn’t run: ${result.message}"
            is ShellRuns.Result.Ran -> {
                val r = result.result
                val output = (if (r.succeeded) r.stdout else r.stderr.ifBlank { r.stdout }).trim()
                when {
                    r.succeeded && output.isEmpty() -> "$name: done"
                    r.succeeded -> output.take(MAX_OUTPUT)
                    else ->
                        "$name exited with ${r.exitCode}" +
                            if (output.isEmpty()) "" else ": ${output.take(MAX_OUTPUT)}"
                }
            }
        }

    private const val MAX_OUTPUT = 2_000
}
