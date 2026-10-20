package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.ui.SwitchRow
import com.droidective.android.ui.automations.AppChooser
import com.droidective.android.ui.copy
import com.droidective.core.apps.ApkComponent
import com.droidective.core.apps.ExtraType
import com.droidective.core.apps.IntentExtra
import com.droidective.core.apps.IntentFlag
import com.droidective.core.apps.IntentKind
import com.droidective.core.apps.IntentResult
import com.droidective.core.apps.IntentSpec
import com.droidective.core.apps.Intents
import com.droidective.core.apps.SavedIntent
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.ApkReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Any intent, sent as the shell: an activity, a broadcast or a service, with its action, data,
 * type, categories, flags and typed extras. A launch it waits for is timed, and a cold start stops
 * the app first, which is how Android Studio measures startup.
 */
@Composable
fun IntentBuilderTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var spec by rememberSaveable(stateSaver = specSaver) { mutableStateOf(IntentSpec()) }
    var result by remember { mutableStateOf<IntentResult?>(null) }
    var saved by remember { mutableStateOf(emptyList<SavedIntent>()) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var reads by remember { mutableStateOf(0) }
    LaunchedEffect(reads) {
        saved = withContext(Dispatchers.IO) { context.droidective.savedIntents.all() }
    }

    ToolScaffold("Intent Builder", tool) {
        if (access.shell == null) {
            ToolNote("Intents are sent with am, as the shell user.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        if (saved.isNotEmpty()) {
            Saved(saved, onLoad = { spec = it.spec }) { name ->
                scope.launch {
                    withContext(Dispatchers.IO) { context.droidective.savedIntents.remove(name) }
                    reads++
                }
            }
        }
        Fields(spec, onChange = { spec = it }, onPickComponent = { dialog = Dialog.App })
        val problem = spec.problem
        problem?.let { ToolNote(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = problem == null,
                onClick = {
                    result = null
                    scope.launch { result = send(context, spec) }
                },
            ) {
                Text("Send")
            }
            OutlinedButton(enabled = problem == null, onClick = { dialog = Dialog.Save }) {
                Text("Save")
            }
            TextButton(
                enabled = problem == null,
                onClick = { context.copy("adb command", Intents.adb(spec)) },
            ) {
                Text("Copy as adb")
            }
        }
        result?.let { Result(it) }
    }

    when (val open = dialog) {
        null -> Unit
        Dialog.App ->
            AppChooser("Which app", onDismiss = { dialog = null }) {
                dialog = Dialog.Components(it.packageName)
            }
        is Dialog.Components ->
            ComponentChooser(open.packageName, spec.kind, onDismiss = { dialog = null }) { name ->
                spec = spec.copy(component = "${open.packageName}/$name", packageName = "")
                dialog = null
            }
        Dialog.Save ->
            SaveDialog(onDismiss = { dialog = null }) { name ->
                dialog = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        context.droidective.savedIntents.save(SavedIntent(name, spec))
                    }
                    reads++
                    tool.say("Saved $name")
                }
            }
    }
}

private sealed interface Dialog {
    data object App : Dialog

    data class Components(val packageName: String) : Dialog

    data object Save : Dialog
}

private val json = Json { ignoreUnknownKeys = true }

private val specSaver: Saver<IntentSpec, String> =
    Saver(
        save = { json.encodeToString(IntentSpec.serializer(), it) },
        restore = { json.decodeFromString(IntentSpec.serializer(), it) },
    )

@Composable
private fun Fields(spec: IntentSpec, onChange: (IntentSpec) -> Unit, onPickComponent: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IntentKind.entries.forEach { kind ->
            FilterChip(
                selected = spec.kind == kind,
                onClick = { onChange(spec.copy(kind = kind)) },
                label = { Text(kind.title) },
            )
        }
    }
    Field("Action", spec.action) { onChange(spec.copy(action = it)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ACTIONS.forEach { (label, action) ->
            FilterChip(
                selected = spec.action == action,
                onClick = {
                    onChange(spec.copy(action = if (spec.action == action) "" else action))
                },
                label = { Text(label) },
            )
        }
    }
    Field("Data", spec.data, keyboard = KeyboardType.Uri) { onChange(spec.copy(data = it)) }
    Field("MIME type", spec.type) { onChange(spec.copy(type = it)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field("Component", spec.component, modifier = Modifier.weight(1f)) {
            onChange(spec.copy(component = it))
        }
        TextButton(onClick = onPickComponent) { Text("Pick") }
    }
    if (spec.component.isBlank()) {
        Field("Package (optional)", spec.packageName) { onChange(spec.copy(packageName = it)) }
    }
    ToolSection("Categories and flags") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CATEGORIES.forEach { (label, category) ->
                val on = category in spec.categories
                FilterChip(
                    selected = on,
                    onClick = {
                        val next =
                            if (on) spec.categories - category else spec.categories + category
                        onChange(spec.copy(categories = next))
                    },
                    label = { Text(label) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntentFlag.entries.forEach { flag ->
                val on = flag in spec.flags
                FilterChip(
                    selected = on,
                    onClick = {
                        onChange(
                            spec.copy(flags = if (on) spec.flags - flag else spec.flags + flag)
                        )
                    },
                    label = { Text(flag.title) },
                )
            }
        }
    }
    Extras(spec.extras) { onChange(spec.copy(extras = it)) }
    if (spec.kind == IntentKind.Activity) {
        SwitchRow(
            "Wait for the launch",
            checked = spec.wait,
            note = "Times it: TotalTime is what Android Studio reports as startup.",
        ) {
            onChange(spec.copy(wait = it))
        }
        SwitchRow(
            "Cold start",
            checked = spec.coldStart,
            note = "Stops the app first, so the time includes starting its process.",
        ) {
            onChange(spec.copy(coldStart = it, wait = it || spec.wait))
        }
    }
}

@Composable
private fun Extras(extras: List<IntentExtra>, onChange: (List<IntentExtra>) -> Unit) {
    ToolSection("Extras") {
        extras.forEachIndexed { index, extra ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExtraType.entries.forEach { type ->
                        FilterChip(
                            selected = extra.type == type,
                            onClick = { onChange(extras.replace(index, extra.copy(type = type))) },
                            label = { Text(type.title) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Key", extra.key, modifier = Modifier.weight(1f)) {
                        onChange(extras.replace(index, extra.copy(key = it)))
                    }
                    Field("Value", extra.value, modifier = Modifier.weight(1f)) {
                        onChange(extras.replace(index, extra.copy(value = it)))
                    }
                }
                TextButton(onClick = { onChange(extras.filterIndexed { i, _ -> i != index }) }) {
                    Text("Remove")
                }
            }
        }
        TextButton(onClick = { onChange(extras + IntentExtra("", ExtraType.Text, "")) }) {
            Text("Add an extra")
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Ascii,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        { onChange(it.trim()) },
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Result(result: IntentResult) {
    ToolSection(if (result.ok) "Sent" else "Not sent") {
        Text(
            result.message,
            color =
                if (result.ok) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        result.activity?.let { InfoRow("Activity", it, copyable = true, mono = true) }
        result.launchState?.let { InfoRow("Launch", it) }
        result.totalMillis?.let { InfoRow("Total time", "$it ms") }
        result.waitMillis?.let { InfoRow("Wait time", "$it ms") }
    }
}

@Composable
private fun Saved(
    saved: List<SavedIntent>,
    onLoad: (SavedIntent) -> Unit,
    onRemove: (String) -> Unit,
) {
    ToolSection("Saved") {
        saved.forEach { intent ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onLoad(intent) }.padding(4.dp)) {
                    Text(intent.name)
                    Text(
                        Intents.command(intent.spec).line.removePrefix("am "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = { onRemove(intent.name) }) { Text("Remove") }
            }
        }
    }
}

/** The app's components of [kind], exported first: the shell can start only those without root. */
@Composable
private fun ComponentChooser(
    packageName: String,
    kind: IntentKind,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var components by remember { mutableStateOf<List<ApkComponent>?>(null) }
    LaunchedEffect(packageName, kind) {
        val wanted =
            when (kind) {
                IntentKind.Activity -> ApkComponent.Kind.Activity
                IntentKind.Broadcast -> ApkComponent.Kind.Receiver
                IntentKind.Service,
                IntentKind.ForegroundService -> ApkComponent.Kind.Service
            }
        components =
            ApkReports.installed(context, packageName)
                ?.components
                ?.filter { it.kind == wanted }
                ?.sortedWith(compareBy({ !it.exported }, { it.name }))
                .orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which one") },
        text = {
            val list = components
            when {
                list == null -> Text("Reading…")
                list.isEmpty() -> Text("It has none of those.")
                else ->
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(list) { component ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { onPick(component.name) }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    component.name.substringAfterLast('.'),
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    if (component.exported) "exported" else "private: needs root",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save intent") },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private suspend fun send(context: android.content.Context, spec: IntentSpec): IntentResult {
    val shell =
        context.droidective.access.shell()
            ?: return IntentResult(false, "Shell isn't running.", null, null, null, null)
    return try {
        val ran = shell.run(Intents.command(spec))
        Intents.parse(ran.stdout + "\n" + ran.stderr)
    } catch (e: ShellUnavailableException) {
        IntentResult(false, e.message ?: "The shell stopped responding.", null, null, null, null)
    }
}

private fun <T> List<T>.replace(index: Int, value: T): List<T> = mapIndexed { i, old ->
    if (i == index) value else old
}

private val ACTIONS =
    listOf(
        "View" to "android.intent.action.VIEW",
        "Main" to "android.intent.action.MAIN",
        "Send" to "android.intent.action.SEND",
    )

private val CATEGORIES =
    listOf(
        "Default" to "android.intent.category.DEFAULT",
        "Browsable" to "android.intent.category.BROWSABLE",
        "Launcher" to "android.intent.category.LAUNCHER",
    )
