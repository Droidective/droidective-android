package com.droidective.android.ui.tools

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.droidective.android.droidective
import com.droidective.android.surface.CommandTaps
import com.droidective.android.surface.ShellRuns
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.CommandOutput
import com.droidective.android.ui.Symbols
import com.droidective.core.commands.CustomCommand
import com.droidective.core.commands.DeepLink
import com.droidective.core.commands.DeepLinkRepository
import com.droidective.core.shell.ShellResult
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CustomCommandsTool(tool: ToolContext) {
    val context = LocalContext.current
    val app = context.droidective
    val scope = rememberCoroutineScope()
    var commands by remember { mutableStateOf<List<CustomCommand>>(emptyList()) }
    var editing by remember { mutableStateOf<CustomCommand?>(null) }
    var output by remember { mutableStateOf<Pair<String, ShellResult>?>(null) }
    var running by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        commands = withContext(Dispatchers.IO) { app.commands.all() }
    }
    LaunchedEffect(Unit) { reload() }
    fun run(command: CustomCommand) {
        scope.launch {
            running = command.id
            val result = CommandTaps.execute(context, command)
            running = null
            if (result is ShellRuns.Result.Ran) Surfaces.refreshBehind(context)
            if (result is ShellRuns.Result.Ran) output = command.name to result.result
            else
                tool.say(
                    CommandOutput.describe(command.name, result),
                    offerSetup = result is ShellRuns.Result.NoShell,
                )
        }
    }
    ToolScaffold("Custom Commands", tool) {
        Button(onClick = { editing = CustomCommand(id = "", name = "", command = "") }) {
            Text("New command")
        }
        if (commands.isEmpty()) {
            ToolNote(
                "Save a shell command to run it from here, a widget, a tile, the notification " +
                    "bar or an automation. It runs as the shell user, so it needs Shell."
            )
        }
        if (commands.isNotEmpty()) {
            ToolSection("Saved") {
                commands.forEach { command ->
                    CommandRow(
                        command,
                        running = running == command.id,
                        canRun = running == null,
                        onRun = { run(command) },
                        onEdit = { editing = command },
                    )
                }
            }
        }
        output?.let { (name, result) ->
            ToolSection("Output") {
                Text(
                    "$name · exit ${result.exitCode}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                val text = (result.stdout + result.stderr).trim()
                SelectionContainer {
                    Text(
                        text.ifEmpty { "No output" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
    editing?.let { command ->
        CommandEditor(
            command,
            onSave = { saved ->
                scope.launch {
                    withContext(Dispatchers.IO) { app.commands.save(saved) }
                    editing = null
                    reload()
                    Surfaces.refresh(context)
                }
            },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        app.commands.delete(command.id)
                        app.layouts.dropCommand(command.id, System.currentTimeMillis())
                    }
                    editing = null
                    output = null
                    reload()
                    Surfaces.refresh(context)
                }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun CommandRow(
    command: CustomCommand,
    running: Boolean,
    canRun: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(command.name, style = MaterialTheme.typography.titleSmall)
        Text(
            command.command,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (command.isToggle) ToolNote("A toggle: runs its off command while it's on.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canRun, onClick = onRun) { Text(if (running) "Running…" else "Run") }
            OutlinedButton(onClick = onEdit) { Text("Edit") }
        }
    }
}

@Composable
private fun CommandEditor(
    command: CustomCommand,
    onSave: (CustomCommand) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = command.id.isEmpty()
    var name by rememberSaveable { mutableStateOf(command.name) }
    var line by rememberSaveable { mutableStateOf(command.command) }
    var isToggle by rememberSaveable { mutableStateOf(command.isToggle) }
    var offLine by rememberSaveable { mutableStateOf(command.offCommand.orEmpty()) }
    var check by rememberSaveable { mutableStateOf(command.check.orEmpty()) }
    var onWhen by rememberSaveable { mutableStateOf(command.onWhenOutputContains.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && line.isNotBlank() && (!isToggle || offLine.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New command" else "Edit command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                MonoField(line, { line = it }, if (isToggle) "On command" else "Command")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Works as a toggle", modifier = Modifier.weight(1f))
                    Switch(checked = isToggle, onCheckedChange = { isToggle = it })
                }
                if (isToggle) {
                    MonoField(offLine, { offLine = it }, "Off command")
                    MonoField(check, { check = it }, "Check command (optional)")
                    OutlinedTextField(
                        value = onWhen,
                        onValueChange = { onWhen = it },
                        label = { Text("On when its output contains") },
                        singleLine = true,
                    )
                }
                if (confirmDelete) {
                    Text(
                        "Delete it? Widgets, tiles and folders that show it lose it too.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        CustomCommand(
                            id =
                                command.id.ifEmpty {
                                    "cmd-${UUID.randomUUID().toString().take(8)}"
                                },
                            name = name.trim(),
                            command = line.trim(),
                            offCommand = offLine.trim().takeIf { isToggle && it.isNotEmpty() },
                            check = check.trim().takeIf { isToggle && it.isNotEmpty() },
                            onWhenOutputContains = onWhen.takeIf { isToggle && it.isNotEmpty() },
                        )
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(
                        onClick = { if (confirmDelete) onDelete() else confirmDelete = true }
                    ) {
                        Text(
                            if (confirmDelete) "Delete" else "Delete…",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun MonoField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        minLines = 2,
    )
}

@Composable
fun DeepLinksTool(tool: ToolContext) {
    val context = LocalContext.current
    val app = context.droidective
    val scope = rememberCoroutineScope()
    var links by remember { mutableStateOf<List<DeepLink>>(emptyList()) }
    var typed by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<DeepLink?>(null) }
    suspend fun reload() {
        links = withContext(Dispatchers.IO) { app.deepLinks.all() }
    }
    LaunchedEffect(Unit) { reload() }
    fun open(uri: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri.trim().toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            scope.launch { tool.say("No app on this phone opens $uri") }
        }
    }
    val typedIsLink = DeepLinkRepository.isLink(typed)
    ToolScaffold("Deep Links", tool) {
        ToolSection("Open a link") {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("myapp://checkout?id=42") },
                isError = typed.isNotBlank() && !typedIsLink,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = typedIsLink, onClick = { open(typed) }) { Text("Open") }
                OutlinedButton(
                    enabled = typedIsLink,
                    onClick = { editing = DeepLink(id = "", label = "", uri = typed.trim()) },
                ) {
                    Text("Save…")
                }
            }
        }
        if (links.isEmpty()) ToolNote("Saved links open in one tap, here or from a widget.")
        else
            ToolSection("Saved") {
                links.forEach { link ->
                    LinkRow(link, onOpen = { open(link.uri) }, onEdit = { editing = link })
                }
            }
    }
    editing?.let { link ->
        LinkEditor(
            link,
            onSave = { saved ->
                scope.launch {
                    withContext(Dispatchers.IO) { app.deepLinks.save(saved) }
                    editing = null
                    reload()
                }
            },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) { app.deepLinks.delete(link.id) }
                    editing = null
                    reload()
                }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun LinkRow(link: DeepLink, onOpen: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp)) {
            Text(link.label)
            Text(
                link.uri,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(painterResource(Symbols.byName.getValue("edit").outlined), "Edit ${link.label}")
        }
    }
}

@Composable
private fun LinkEditor(
    link: DeepLink,
    onSave: (DeepLink) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = link.id.isEmpty()
    var label by rememberSaveable { mutableStateOf(link.label) }
    var uri by rememberSaveable { mutableStateOf(link.uri) }
    val valid = label.isNotBlank() && DeepLinkRepository.isLink(uri)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Save link" else "Edit link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uri = it },
                    label = { Text("Link") },
                    isError = uri.isNotBlank() && !DeepLinkRepository.isLink(uri),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        DeepLink(
                            id = link.id.ifEmpty { "link-${UUID.randomUUID().toString().take(8)}" },
                            label = label.trim(),
                            uri = uri.trim(),
                        )
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
