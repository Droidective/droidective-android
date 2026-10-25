package com.droidective.android.ui.automations

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.droidective.android.R
import com.droidective.android.automation.Automations
import com.droidective.android.automation.RunAutomationActivity
import com.droidective.android.automation.RunRequest
import com.droidective.android.droidective
import com.droidective.android.ui.AdaptiveColumns
import com.droidective.android.ui.AdaptiveRows
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.copy
import com.droidective.android.ui.tools.ToolContext
import com.droidective.android.ui.tools.ToolNote
import com.droidective.android.ui.tools.ToolScaffold
import com.droidective.android.ui.tools.ToolSection
import com.droidective.core.automations.Automation
import com.droidective.core.automations.AutomationRules
import com.droidective.core.automations.AutomationTransfer
import com.droidective.core.automations.Event
import com.droidective.core.automations.Review
import com.droidective.core.automations.RunRecord
import com.droidective.core.automations.Trigger
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the Automations tab is: the list, History, or an automation being edited. */
object AutomationsRoute {
    const val HISTORY = "history"
    const val NEW = "new"
}

/**
 * The Automations tab.
 *
 * @param open null for the list, [AutomationsRoute.HISTORY], [AutomationsRoute.NEW], or an
 *   automation's id to edit it.
 */
@Composable
fun AutomationsScreen(open: String?, onOpen: (String?) -> Unit, onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val tool = remember { ToolContext(snackbar, onBack = { onOpen(null) }, onOpenSetup) }
    when (open) {
        null -> AutomationList(tool, onOpen)
        AutomationsRoute.HISTORY -> HistoryScreen(tool)
        AutomationsRoute.NEW -> AutomationEditor(original = null, tool)
        else ->
            key(open) {
                var original by remember { mutableStateOf<Automation?>(null) }
                LaunchedEffect(open) {
                    original =
                        withContext(Dispatchers.IO) { context.droidective.automations.get(open) }
                }
                original?.let { AutomationEditor(it, tool) }
            }
    }
}

@Composable
private fun AutomationList(tool: ToolContext, onOpen: (String?) -> Unit) {
    val context = LocalContext.current
    val app = context.droidective
    val scope = rememberCoroutineScope()
    var automations by remember { mutableStateOf<List<Automation>>(emptyList()) }
    var history by remember { mutableStateOf<List<RunRecord>>(emptyList()) }
    var reviewing by remember { mutableStateOf<Automation?>(null) }
    suspend fun reload() {
        withContext(Dispatchers.IO) {
            automations = app.automations.all()
            history = app.ledger.history()
        }
    }
    LaunchedEffect(Unit) { reload() }
    fun save(automation: Automation) {
        scope.launch {
            withContext(Dispatchers.IO) {
                app.automations.save(automation)
                // Switching a While off ends it, which puts its toggles back.
                if (!automation.enabled && automation.isWhile) {
                    Automations.enqueue(
                        context,
                        RunRequest(automation.id, RunRequest.Kind.Finish, "It was switched off"),
                    )
                }
                Automations.reschedule(context)
            }
            reload()
        }
    }
    val transfer = rememberTransfer(tool) { scope.launch { reload() } }
    ToolScaffold("Automations", tool, back = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onOpen(AutomationsRoute.NEW) }) { Text("New automation") }
            OutlinedButton(onClick = { onOpen(AutomationsRoute.HISTORY) }) { Text("History") }
            Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(Symbols.byName.getValue("more_vert").outlined), "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Import…") },
                        onClick = {
                            menu = false
                            transfer.import()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export all…") },
                        enabled = automations.isNotEmpty(),
                        onClick = {
                            menu = false
                            transfer.export(automations)
                        },
                    )
                }
            }
        }
        if (automations.isEmpty()) Examples { example -> save(example) }
        AdaptiveColumns { columns ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AdaptiveRows(automations, columns) { automation, modifier ->
                    AutomationCard(
                        automation,
                        last = history.firstOrNull { it.automationId == automation.id },
                        onEnabled = { on ->
                            if (on) reviewing = automation
                            else save(automation.copy(enabled = false))
                        },
                        onRun = {
                            Automations.runNow(context, automation.id, Automations.CAUSE_MANUAL)
                            scope.launch {
                                tool.say("Running “${automation.name}”. History has how it went.")
                            }
                        },
                        onEdit = { onOpen(automation.id) },
                        onExport = { transfer.export(listOf(automation)) },
                        modifier = modifier,
                    )
                }
            }
        }
    }
    reviewing?.let { automation ->
        ReviewDialog(
            automation,
            onDismiss = { reviewing = null },
            onTurnOn = {
                reviewing = null
                save(automation.copy(enabled = true))
            },
        )
    }
}

@Composable
private fun AutomationCard(
    automation: Automation,
    last: RunRecord?,
    onEnabled: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val problem = remember(automation) { AutomationRules.problems(automation).firstOrNull() }
    val manual = (automation.trigger as? Trigger.On)?.event == Event.Manual
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(automation.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        automation.trigger.describe(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = automation.enabled,
                    onCheckedChange = onEnabled,
                    modifier = Modifier.semantics { contentDescription = automation.name },
                )
            }
            automation.conditions.forEach { ToolNote(it.describe()) }
            ToolNote(
                if (automation.actions.size == 1) "1 action"
                else "${automation.actions.size} actions"
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            last?.let { ToolNote(lastRun(it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (automation.enabled && !automation.isWhile) {
                    TextButton(onClick = onRun) { Text("Run") }
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(Symbols.byName.getValue("more_vert").outlined), "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (manual) {
                            DropdownMenuItem(
                                text = { Text("Add to home screen") },
                                onClick = {
                                    menu = false
                                    pinShortcut(context, automation)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Copy the command for the Mac") },
                            onClick = {
                                menu = false
                                context.copy("adb command", macCommand(automation.id))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export…") },
                            onClick = {
                                menu = false
                                onExport()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** `adb shell am broadcast …`, the way a Mac custom command runs this automation. */
fun macCommand(automationId: String): String =
    "adb shell am broadcast -n com.droidective.android/.RunAutomation --es id $automationId"

private fun lastRun(run: RunRecord): String {
    val time =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(run.at))
    val how =
        when {
            run.skipped != null -> "didn’t run: ${run.skipped}"
            run.ok -> "done"
            else -> "something failed; History says what"
        }
    return "Last fired $time, $how"
}

private fun pinShortcut(context: Context, automation: Automation) {
    val intent =
        Intent(context, RunAutomationActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(RunAutomationActivity.EXTRA_ID, automation.id)
    val shortcut =
        ShortcutInfoCompat.Builder(context, "automation-${automation.id}")
            .setShortLabel(automation.name.take(SHORTCUT_LABEL))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
    ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
}

private const val SHORTCUT_LABEL = 25

/**
 * Everything it will do, spelled out, before it's switched on. An imported automation arrives
 * switched off so that this is the first thing anyone sees of it.
 */
@Composable
private fun ReviewDialog(automation: Automation, onDismiss: () -> Unit, onTurnOn: () -> Unit) {
    val context = LocalContext.current
    val lines = remember(automation) { Review.lines(automation, context.droidective.commands::get) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on “${automation.name}”?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(automation.trigger.describe())
                automation.conditions.forEach { Text(it.describe()) }
                lines.forEach { line ->
                    Text(
                        (if (line.destructive) "Can’t be undone: " else "") + line.text,
                        color =
                            if (line.destructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                    )
                    line.shell.forEach {
                        Text(
                            it,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onTurnOn) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HistoryScreen(tool: ToolContext) {
    val context = LocalContext.current
    val app = context.droidective
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<RunRecord>>(emptyList()) }
    LaunchedEffect(Unit) { history = withContext(Dispatchers.IO) { app.ledger.history() } }
    ToolScaffold("History", tool) {
        if (history.isEmpty()) ToolNote("Nothing has run yet.")
        else
            OutlinedButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.ledger.clearHistory() }
                        history = emptyList()
                    }
                }
            ) {
                Text("Clear history")
            }
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        history.forEach { run ->
            ToolSection(format.format(Date(run.at))) {
                Text(run.name, style = MaterialTheme.typography.titleSmall)
                ToolNote(run.cause)
                run.skipped?.let {
                    Text("Didn’t run: $it", color = MaterialTheme.colorScheme.error)
                }
                run.steps.forEach { step ->
                    Text(
                        (if (step.ok) "✓ " else "✗ ") + step.description,
                        color =
                            if (step.ok) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                    )
                    step.detail?.let { ToolNote(it) }
                }
            }
        }
    }
}

/** Import from and export to a file the person picks. */
private class Transfer(val import: () -> Unit, val export: (List<Automation>) -> Unit)

@Composable
private fun rememberTransfer(tool: ToolContext, onImported: () -> Unit): Transfer {
    val context = LocalContext.current
    val app = context.droidective
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<Automation>>(emptyList()) }
    val exporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val text = AutomationTransfer.export(pending, app.commands::get)
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    }
                }
                tool.say("Exported ${pending.size} automations")
            }
        }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val message =
                    withContext(Dispatchers.IO) {
                        val text =
                            context.contentResolver
                                .openInputStream(uri)
                                ?.use { it.readBytes().decodeToString() }
                                .orEmpty()
                        importText(context, text)
                    }
                onImported()
                tool.say(message)
            }
        }
    return remember {
        Transfer(
            import = {
                importer.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream")
                )
            },
            export = { automations ->
                pending = automations
                exporter.launch("droidective-automations.json")
            },
        )
    }
}

private fun importText(context: Context, text: String): String {
    val app = context.droidective
    return when (
        val result =
            AutomationTransfer.import(
                text,
                app.automations.all().map { it.id }.toSet(),
                app.commands.all(),
            )
    ) {
        is AutomationTransfer.Result.Failed -> result.reason
        is AutomationTransfer.Result.Imported -> {
            result.commands.forEach(app.commands::save)
            result.automations.forEach(app.automations::save)
            "Imported ${result.automations.size} automations, switched off. Turning one on " +
                "shows everything it does first."
        }
    }
}
