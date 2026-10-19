package com.droidective.android.ui.tools

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.copy
import com.droidective.core.device.SystemValue
import com.droidective.core.device.SystemValues
import com.droidective.core.device.ValueChange
import com.droidective.core.device.ValueEditor
import com.droidective.core.device.ValueOutcome
import com.droidective.core.device.ValueTable
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android's settings tables, its system properties and its feature flags, searched and changed.
 * Every change is on record with what it replaced, and stays undoable after the app restarts.
 */
@Composable
fun SettingsEditorTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    var table by rememberSaveable { mutableStateOf(ValueTable.Global) }
    var reads by remember { mutableIntStateOf(0) }
    var values by remember { mutableStateOf<Result<List<SystemValue>>?>(null) }
    var history by remember { mutableStateOf(emptyList<ValueChange>()) }
    var held by remember { mutableStateOf<Boolean?>(null) }
    var editing by remember { mutableStateOf<SystemValue?>(null) }
    val scope = rememberCoroutineScope()
    fun edit(block: suspend (ValueEditor) -> String) {
        scope.launch {
            tool.say(withContext(Dispatchers.IO) { editValues(context, block) })
            reads++
        }
    }

    LaunchedEffect(reads) {
        history = withContext(Dispatchers.IO) { context.droidective.valueChanges.all() }
    }
    LaunchedEffect(table, reads, access.shell) {
        if (access.shell == null) return@LaunchedEffect
        if (table == ValueTable.Flags) held = readHold(context)
        values = readTable(context, table)
    }

    ToolScaffold("Settings Editor", tool) {
        if (access.shell == null) {
            ToolNote("Settings, properties and flags are changed as the shell user.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        if (history.isNotEmpty()) {
            Changes(
                history,
                onUndo = { change -> edit { undoMessage(change, it.undo(change)) } },
                onUndoAll = { edit { editor -> undoAll(history, editor) } },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ValueTable.entries.forEach { option ->
                FilterChip(
                    selected = table == option,
                    onClick = {
                        if (table != option) values = null
                        table = option
                    },
                    label = { Text(option.title) },
                )
            }
        }
        if (table == ValueTable.Flags) {
            if (access.shell?.isRoot != true) {
                ToolNote(
                    "As the shell user, Android changes only the flags on its allowlist. Root " +
                        "changes any of them."
                )
            }
            HoldFlags(held) { hold ->
                scope.launch {
                    val command = if (hold) SystemValues.holdFlags else SystemValues.releaseFlags
                    val failed = runOne(context, command)
                    held = readHold(context)
                    tool.say(
                        failed
                            ?: if (hold) "Flags stay as set until the phone restarts"
                            else "Flags sync again"
                    )
                }
            }
        }
        Values(
            table,
            values,
            onOpen = { editing = it },
            onAdd = { editing = SystemValue("", null) },
        )
    }

    editing?.let { value ->
        ValueDialog(
            table,
            value,
            onDismiss = { editing = null },
            onSet = { key, newValue ->
                editing = null
                edit { setMessage(key, newValue, it.set(table, key, newValue)) }
            },
        )
    }
}

@Composable
private fun Changes(
    changes: List<ValueChange>,
    onUndo: (ValueChange) -> Unit,
    onUndoAll: () -> Unit,
) {
    ToolSection("Changed here") {
        changes.forEach { change ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(4.dp)) {
                    Text(
                        change.key,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${change.table.title} · ${shown(change.before)} → ${shown(change.after)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onUndo(change) }) { Text("Undo") }
            }
        }
        if (changes.size > 1) {
            OutlinedButton(onClick = onUndoAll) { Text("Undo all ${changes.size}") }
        }
        ToolNote("Undo puts back the value from before the first change.")
    }
}

@Composable
private fun HoldFlags(held: Boolean?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(
                value = held == true,
                enabled = held != null,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Keep flags as set")
            Text(
                "Google Play services puts the flags it manages back. This stops it until the " +
                    "phone restarts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = held == true, enabled = held != null, onCheckedChange = null)
    }
}

@Composable
private fun Values(
    table: ValueTable,
    values: Result<List<SystemValue>>?,
    onOpen: (SystemValue) -> Unit,
    onAdd: () -> Unit,
) {
    var search by rememberSaveable(table) { mutableStateOf("") }
    var limit by rememberSaveable(table, search) { mutableIntStateOf(PAGE) }
    val all = values?.getOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            search,
            { search = it },
            placeholder = { Text(all?.let { "Search ${it.size} values" } ?: "Search") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAdd) { Text("Add") }
    }
    when {
        values == null -> ToolNote("Reading…")
        all == null -> ToolNote(values.exceptionOrNull()?.message ?: "They couldn't be read.")
        else -> {
            val term = search.trim()
            val shown = all.filter {
                it.key.contains(term, ignoreCase = true) ||
                    it.value?.contains(term, ignoreCase = true) == true
            }
            ToolSection(table.title) {
                shown.take(limit).forEach { ValueRow(table, it) { onOpen(it) } }
                if (shown.isEmpty()) ToolNote("Nothing matches that.")
                if (shown.size > limit) {
                    TextButton(onClick = { limit += PAGE }) {
                        Text("Show more (${shown.size - limit} left)")
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueRow(table: ValueTable, value: SystemValue, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(4.dp)) {
        if (table == ValueTable.Flags) {
            Text(
                value.key.substringBefore('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            if (table == ValueTable.Flags) value.key.substringAfter('/') else value.key,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            shown(value.value),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Edits [value], or makes a new one when its key is empty. [onSet] takes null to delete. */
@Composable
private fun ValueDialog(
    table: ValueTable,
    value: SystemValue,
    onDismiss: () -> Unit,
    onSet: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    val isNew = value.key.isEmpty()
    var key by rememberSaveable { mutableStateOf(value.key) }
    var text by rememberSaveable { mutableStateOf(value.value.orEmpty()) }
    val editable = SystemValues.editable(table, key)
    val validKey =
        key.isNotBlank() &&
            (table != ValueTable.Flags ||
                key.substringBefore('/', "").isNotEmpty() &&
                    key.substringAfter('/', "").isNotEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle(table, isNew)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isNew) {
                    MonoValueField(
                        key,
                        { key = it.trim() },
                        if (table == ValueTable.Flags) "namespace/name" else "Key",
                    )
                } else {
                    Text(key, fontFamily = FontFamily.Monospace)
                }
                MonoValueField(text, { text = it }, "Value", enabled = editable)
                if (!editable) ToolNote(FIXED)
                if (table == ValueTable.Secure || table == ValueTable.Global) {
                    ToolNote("A wrong value can change how the phone behaves; Undo puts it back.")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = editable && validKey, onClick = { onSet(key, text) }) {
                Text("Set")
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { context.copy(key, text) }) { Text("Copy") }
                }
                if (!isNew && table.deletable && value.value != null) {
                    TextButton(onClick = { onSet(key, null) }) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun MonoValueField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        maxLines = 6,
    )
}

private fun dialogTitle(table: ValueTable, isNew: Boolean): String {
    val noun =
        when (table) {
            ValueTable.Props -> "property"
            ValueTable.Flags -> "flag"
            else -> "${table.title.lowercase()} setting"
        }
    return if (isNew) "New $noun" else noun.replaceFirstChar { it.uppercase() }
}

private fun shown(value: String?): String =
    when (value) {
        null -> "no value"
        "" -> "empty"
        else -> value
    }

private suspend fun readTable(context: Context, table: ValueTable): Result<List<SystemValue>> {
    val shell =
        context.droidective.access.shell()
            ?: return Result.failure(IllegalStateException("Shell isn't running."))
    return try {
        val result = shell.run(SystemValues.list(table))
        if (result.succeeded) {
            Result.success(
                withContext(Dispatchers.Default) { SystemValues.parse(table, result.stdout) }
            )
        } else {
            Result.failure(IllegalStateException(Parse.failure(result.stderr, result.stdout)))
        }
    } catch (e: ShellUnavailableException) {
        Result.failure(e)
    }
}

/** Runs [block] with an editor over the current shell, and returns what to say. */
private suspend fun editValues(context: Context, block: suspend (ValueEditor) -> String): String {
    val shell = context.droidective.access.shell() ?: return "Shell isn't running."
    val message = block(ValueEditor(shell, context.droidective.valueChanges))
    Surfaces.refreshBehind(context)
    return message
}

private suspend fun runOne(context: Context, command: ShellCommand): String? {
    val shell = context.droidective.access.shell() ?: return "Shell isn't running."
    return try {
        val result = shell.run(command)
        if (result.succeeded) null
        else Parse.failure(result.stderr, result.stdout) ?: "It exited with ${result.exitCode}."
    } catch (e: ShellUnavailableException) {
        e.message ?: "The shell stopped responding."
    }
}

/** Whether flags are held, or null when that can't be read. */
private suspend fun readHold(context: Context): Boolean? {
    val shell = context.droidective.access.shell() ?: return null
    return try {
        shell
            .run(SystemValues.flagsHoldState)
            .takeIf { it.succeeded }
            ?.stdout
            ?.let(SystemValues::flagsHeld)
    } catch (_: ShellUnavailableException) {
        null
    }
}

private fun setMessage(key: String, wanted: String?, outcome: ValueOutcome): String =
    when (outcome) {
        is ValueOutcome.Changed ->
            when {
                outcome.change.after == wanted && wanted == null -> "Deleted $key"
                outcome.change.after == wanted -> "Set $key"
                else -> "Android set $key to ${shown(outcome.change.after)}"
            }
        ValueOutcome.Unchanged -> "$key already held that"
        is ValueOutcome.Kept -> "Android kept $key at ${shown(outcome.value)}"
        is ValueOutcome.Refused -> outcome.reason
        ValueOutcome.Undone -> "Put back $key"
    }

private fun undoMessage(change: ValueChange, outcome: ValueOutcome): String =
    when (outcome) {
        ValueOutcome.Undone -> "Put back ${change.key}"
        is ValueOutcome.Kept -> "${change.key} is still ${shown(outcome.value)}"
        else -> setMessage(change.key, change.before, outcome)
    }

private suspend fun undoAll(changes: List<ValueChange>, editor: ValueEditor): String {
    val failed = changes.count { editor.undo(it) != ValueOutcome.Undone }
    return if (failed == 0) "Put back ${changes.size} values"
    else "$failed of ${changes.size} couldn't be put back"
}

private const val PAGE = 200
private const val FIXED =
    "Read-only (ro.) properties are set as the phone starts and can’t be changed."
