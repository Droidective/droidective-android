package com.droidective.android.ui.tools

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.droidective.android.droidective
import com.droidective.android.surface.ShellRuns
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.CommandOutput
import com.droidective.android.ui.LevelChip
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.ToggleRow
import com.droidective.android.ui.copy
import com.droidective.android.ui.rememberToggleStates
import com.droidective.android.ui.tapToggle
import com.droidective.core.Access
import com.droidective.core.shell.ShellCommand
import com.droidective.core.toggles.ToggleDef
import com.droidective.core.toggles.Toggles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything a tool screen needs from the frame around it. */
class ToolContext(
    val snackbar: SnackbarHostState,
    val onBack: () -> Unit,
    val onOpenSetup: () -> Unit,
) {
    /** Shows [message]; when [offerSetup], with a Set up button that opens Setup. */
    suspend fun say(message: String, offerSetup: Boolean = false) {
        val result =
            snackbar.showSnackbar(message, actionLabel = if (offerSetup) "Set up" else null)
        if (result == SnackbarResult.ActionPerformed) onOpenSetup()
    }
}

/**
 * A tool's frame: back arrow, title, and a scrolling column; system back goes back too. A tab's own
 * top screen passes [back] false, so system back leaves the app as usual.
 */
@Composable
fun ToolScaffold(
    title: String,
    tool: ToolContext,
    back: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = back, onBack = tool.onBack)
    Scaffold(snackbarHost = { SnackbarHost(tool.snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (back) ToolTitle(title, tool)
            else Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}

@Composable
fun ToolTitle(title: String, tool: ToolContext) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = tool.onBack) {
            Icon(
                painterResource(Symbols.byName.getValue("arrow_back").outlined),
                contentDescription = "Back",
            )
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

/** A titled card that groups related rows. */
@Composable
fun ToolSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
            content()
        }
    }
}

/** A label and its value; tapping a copyable row copies the value. */
@Composable
fun InfoRow(label: String, value: String?, copyable: Boolean = false, mono: Boolean = false) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth()
            .let {
                if (copyable && value != null) it.clickable { context.copy(label, value) } else it
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value ?: "—",
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(0.58f),
        )
    }
}

/**
 * The toggles with [ids], live, in the order given. An on/off toggle flips on tap; one with more
 * states shows them all, since cycling through three to reach one is guesswork.
 */
@Composable
fun ToggleRows(ids: List<String>, tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val toggles = remember(ids) { ids.mapNotNull { Toggles.byId(it) }.filter { it.isBuilt } }
    val states = rememberToggleStates(toggles)
    fun tap(toggle: ToggleDef, state: String?) =
        scope.tapToggle(context, toggle, tool.snackbar, tool.onOpenSetup, state)
    toggles.forEach { toggle ->
        if (toggle.states.size > 2) {
            ToggleChoice(toggle, states[toggle.id], access) { tap(toggle, it) }
        } else {
            ToggleRow(toggle, states[toggle.id], access, containerColor = Color.Transparent) {
                tap(toggle, null)
            }
        }
    }
}

@Composable
private fun ToggleChoice(
    toggle: ToggleDef,
    state: String?,
    access: Access,
    onChoose: (String) -> Unit,
) {
    val symbol = Symbols.byName.getValue(toggle.icon)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painterResource(symbol.outlined),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                toggle.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            LevelChip(toggle.level, missing = !access.allows(toggle.level))
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            toggle.states.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option.id == state,
                    onClick = { onChoose(option.id) },
                    shape = SegmentedButtonDefaults.itemShape(index, toggle.states.size),
                ) {
                    Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A note under a section, in the quieter colour. */
@Composable
fun ToolNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** Runs [block] now and every [periodMillis] while the screen is visible, and not otherwise. */
@Composable
fun WhileVisible(periodMillis: Long, block: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(block)
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                current()
                delay(periodMillis)
            }
        }
    }
}

/** Shows [message] in the tool's snackbar. */
@Composable
fun rememberSay(tool: ToolContext): (String) -> Unit {
    val scope = rememberCoroutineScope()
    return { message -> scope.launch { tool.say(message) } }
}

/** Runs shell lines for a tool and says how it went; a missing shell offers Setup. */
class ToolShell(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tool: ToolContext,
) {
    /**
     * @param title what the person asked for, such as "Clear data", for the failure message.
     * @param done what to say when every line succeeded.
     * @param after runs before the message shows, with whether every line succeeded.
     */
    fun run(
        title: String,
        commands: List<ShellCommand>,
        done: String,
        after: (Boolean) -> Unit = {},
    ) {
        scope.launch {
            val result = ShellRuns.execute(context, commands)
            if (result is ShellRuns.Result.Ran) Surfaces.refreshBehind(context)
            val succeeded = result is ShellRuns.Result.Ran && result.result.succeeded
            after(succeeded)
            tool.say(
                if (succeeded) done else CommandOutput.describe(title, result),
                offerSetup = result is ShellRuns.Result.NoShell,
            )
        }
    }
}

@Composable
fun rememberToolShell(tool: ToolContext): ToolShell {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(tool) { ToolShell(context, scope, tool) }
}
