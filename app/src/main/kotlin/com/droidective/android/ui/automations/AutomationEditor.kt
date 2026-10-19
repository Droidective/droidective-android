package com.droidective.android.ui.automations

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.droidective.android.automation.AutomationRunner
import com.droidective.android.automation.Automations
import com.droidective.android.automation.RunRequest
import com.droidective.android.droidective
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.openSafely
import com.droidective.android.ui.openUsageAccess
import com.droidective.android.ui.tools.ToolContext
import com.droidective.android.ui.tools.ToolNote
import com.droidective.android.ui.tools.ToolScaffold
import com.droidective.android.ui.tools.ToolSection
import com.droidective.core.automations.Action
import com.droidective.core.automations.Automation
import com.droidective.core.automations.AutomationRules
import com.droidective.core.automations.Condition
import com.droidective.core.automations.Event
import com.droidective.core.automations.Review
import com.droidective.core.automations.State
import com.droidective.core.automations.Trigger
import com.droidective.core.automations.isDestructive
import com.droidective.platform.CrashLog
import com.droidective.platform.ForegroundApp
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val draftSaver =
    Saver<Automation, String>(
        save = { Json.encodeToString(Automation.serializer(), it) },
        restore = { Json.decodeFromString(Automation.serializer(), it) },
    )

private fun blank() =
    Automation(
        id = "auto-${UUID.randomUUID().toString().take(8)}",
        name = "",
        trigger = Trigger.On(Event.Manual),
        actions = emptyList(),
    )

/**
 * A new automation, or [original] to change. Saving checks [AutomationRules] first and shows what
 * to fix instead of saving something the runtime would refuse.
 */
@Composable
fun AutomationEditor(original: Automation?, tool: ToolContext) {
    val context = LocalContext.current
    val app = context.droidective
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(original ?: blank()) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    ToolScaffold(if (original == null) "New automation" else "Edit automation", tool) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TriggerSection(draft, onChange = { draft = it }, onDialog = { dialog = it })
        ConditionsSection(draft, onChange = { draft = it }, onDialog = { dialog = it })
        AppOpenNote(draft)
        CrashNote(draft)
        ActionsSection(draft, onChange = { draft = it }, onDialog = { dialog = it })
        if (problems.isNotEmpty()) {
            ToolSection("Before it can be saved") {
                problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    problems = AutomationRules.problems(draft)
                    if (problems.isEmpty()) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                app.automations.save(draft)
                                Automations.reschedule(context)
                            }
                            tool.onBack()
                        }
                    }
                }
            ) {
                Text("Save")
            }
            if (original != null) {
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
    }
    dialog?.let { current ->
        EditorDialogs(current, draft, onChange = { draft = it }, onDialog = { dialog = it })
    }
    if (confirmDelete && original != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${original.name}”?") },
            text = { Text("A While that's running puts its toggles back first.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                // End a running While first, or its toggles would stay set.
                                AutomationRunner(context)
                                    .fire(
                                        RunRequest(
                                            original.id,
                                            RunRequest.Kind.Finish,
                                            "It was deleted",
                                        )
                                    )
                                app.automations.delete(original.id)
                                Automations.reschedule(context)
                            }
                            tool.onBack()
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TriggerSection(
    draft: Automation,
    onChange: (Automation) -> Unit,
    onDialog: (EditorDialog) -> Unit,
) {
    val context = LocalContext.current
    ToolSection("When") {
        Text(draft.trigger.describe(), modifier = Modifier.padding(horizontal = 4.dp))
        TextButton(onClick = { onDialog(EditorDialog.TriggerKinds) }) { Text("Change") }
        if (draft.isWhile) {
            ToolNote(
                "It sets its toggles while that holds, and puts each one back when it ends, " +
                    "unless you changed it yourself in between."
            )
        }
        val schedule = (draft.trigger as? Trigger.On)?.event as? Event.Schedule
        if (schedule != null) {
            OutlinedButton(onClick = { onDialog(EditorDialog.ScheduleTime) }) {
                Text("Change the time")
            }
            DayChips(schedule.days) { days ->
                onChange(draft.copy(trigger = Trigger.On(schedule.copy(days = days))))
            }
            if (!Automations.exactAlarms(context)) {
                ToolNote("Without Alarms & reminders, Android can run it up to 10 minutes late.")
                OutlinedButton(
                    onClick = {
                        context.openSafely(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                "package:${context.packageName}".toUri(),
                            )
                        )
                    }
                ) {
                    Text("Allow Alarms & reminders")
                }
            }
        }
        if ((draft.trigger as? Trigger.On)?.event == Event.Manual) {
            ToolNote(
                "Run it from its card, a home-screen shortcut, or the Mac: " +
                    "adb shell am broadcast -n com.droidective.android/.RunAutomation " +
                    "--es id ${draft.id}"
            )
        }
    }
}

@Composable
private fun ConditionsSection(
    draft: Automation,
    onChange: (Automation) -> Unit,
    onDialog: (EditorDialog) -> Unit,
) {
    ToolSection("Only if") {
        if (draft.conditions.isEmpty()) ToolNote("Always, when it fires.")
        draft.conditions.forEachIndexed { index, condition ->
            RemovableRow(condition.describe()) {
                onChange(
                    draft.copy(conditions = draft.conditions.filterIndexed { i, _ -> i != index })
                )
            }
        }
        TextButton(onClick = { onDialog(EditorDialog.ConditionKinds) }) { Text("Add a condition") }
    }
}

@Composable
private fun ActionsSection(
    draft: Automation,
    onChange: (Automation) -> Unit,
    onDialog: (EditorDialog) -> Unit,
) {
    val context = LocalContext.current
    val commands = context.droidective.commands
    fun move(from: Int, to: Int) {
        if (to !in draft.actions.indices) return
        val list = draft.actions.toMutableList()
        list.add(to, list.removeAt(from))
        onChange(draft.copy(actions = list))
    }
    ToolSection("Do") {
        if (draft.actions.isEmpty()) ToolNote("Nothing yet. Add what it should do, in order.")
        draft.actions.forEachIndexed { index, action ->
            ActionRow(
                Review.describe(action, commands::get),
                destructive = action.isDestructive,
                onUp = { move(index, index - 1) },
                onDown = { move(index, index + 1) },
                onRemove = {
                    onChange(
                        draft.copy(actions = draft.actions.filterIndexed { i, _ -> i != index })
                    )
                },
            )
            if (action is Action.Notify) {
                action.buttons.forEachIndexed { b, button ->
                    RemovableRow("   Button “${button.label}”") {
                        val buttons = action.buttons.filterIndexed { i, _ -> i != b }
                        onChange(draft.replaceAction(index, action.copy(buttons = buttons)))
                    }
                }
                if (action.buttons.size < 3) {
                    TextButton(onClick = { onDialog(EditorDialog.ButtonTarget(index)) }) {
                        Text("Add a button")
                    }
                }
            }
        }
        TextButton(onClick = { onDialog(EditorDialog.ActionKinds) }) { Text("Add an action") }
        if (draft.actions.any { it.opensSomething } && !Settings.canDrawOverlays(context)) {
            ToolNote(
                "Android only lets an app open screens from the background with Display over " +
                    "other apps, or through Shell. Without either, you get a notification to tap."
            )
            OutlinedButton(
                onClick = {
                    context.openSafely(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri(),
                        )
                    )
                }
            ) {
                Text("Allow Display over other apps")
            }
        }
    }
}

private val Action.opensSomething: Boolean
    get() =
        this is Action.OpenApp ||
            this is Action.OpenLink ||
            this is Action.OpenShortcut ||
            this is Action.OpenSettings

internal fun Automation.replaceAction(index: Int, action: Action) =
    copy(actions = actions.toMutableList().apply { set(index, action) })

internal fun Automation.withCondition(condition: Condition) =
    copy(conditions = conditions + condition)

internal fun Automation.withAction(action: Action) = copy(actions = actions + action)

@Composable
private fun RemovableRow(text: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        IconButton(onClick = onRemove) {
            Icon(painterResource(Symbols.byName.getValue("close").outlined), "Remove")
        }
    }
}

@Composable
private fun ActionRow(
    text: String,
    destructive: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(text)
            if (destructive) {
                Text(
                    "Can’t be undone",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        IconButton(onClick = onUp) {
            Icon(painterResource(Symbols.byName.getValue("arrow_upward").outlined), "Move up")
        }
        IconButton(onClick = onDown) {
            Icon(painterResource(Symbols.byName.getValue("arrow_downward").outlined), "Move down")
        }
        IconButton(onClick = onRemove) {
            Icon(painterResource(Symbols.byName.getValue("close").outlined), "Remove")
        }
    }
}

/** "An app is open" needs Shell or usage access; say so, with the way to allow it. */
@Composable
private fun AppOpenNote(draft: Automation) {
    val context = LocalContext.current
    val app = context.droidective
    val uses =
        (draft.conditions.map { it.state } + listOfNotNull(draft.trigger.state)).any {
            it is State.AppOpen
        }
    if (!uses || app.access.shell() != null || ForegroundApp.usageAccess(context)) return
    ToolNote(
        "Telling which app is open needs Shell, or Usage access for Droidective. Without " +
            "either, it never counts as open."
    )
    OutlinedButton(onClick = { context.openUsageAccess() }) { Text("Allow Usage access") }
}

/** A crash trigger hears nothing until crash reports can be read; say what's missing. */
@Composable
private fun CrashNote(draft: Automation) {
    val context = LocalContext.current
    if ((draft.trigger as? Trigger.On)?.event !is Event.AppCrashed) return
    when (CrashLog(context).access()) {
        CrashLog.Access.Ready -> Unit
        CrashLog.Access.NeedsGrant ->
            ToolNote(
                "Hearing about crashes needs the crash-report permission that comes with the " +
                    "Granted level. Set it up in Setup, or let Shell grant it."
            )
        CrashLog.Access.NeedsUsageAccess -> {
            ToolNote("Telling which app crashed needs Usage access for Droidective.")
            OutlinedButton(onClick = { context.openUsageAccess() }) { Text("Allow Usage access") }
        }
    }
}

private val Trigger.state: State?
    get() =
        when (this) {
            is Trigger.Starts -> state
            is Trigger.Ends -> state
            is Trigger.While -> state
            is Trigger.On -> null
        }
