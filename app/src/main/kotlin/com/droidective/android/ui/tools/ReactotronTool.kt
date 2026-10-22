package com.droidective.android.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.reactotron.Reactotron
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.copy
import com.droidective.core.reactotron.ReactotronEvent
import com.droidective.core.reactotron.ReactotronKind
import com.droidective.core.reactotron.ReactotronLabels
import com.droidective.core.reactotron.ReactotronTimeline
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reactotron on the phone: start it, see which apps are connected, and read what they said — logs,
 * API calls, state — newest first. The list catches up a few times a second rather than on every
 * command, so a chatty app can't make it lay out a hundred times a second.
 */
@Composable
fun ReactotronTool(tool: ToolContext) {
    var entries by remember { mutableStateOf(Reactotron.timeline.snapshot()) }
    LaunchedEffect(Unit) {
        Reactotron.changes.collect {
            entries = Reactotron.timeline.snapshot()
            delay(PACE_MILLIS)
        }
    }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    val kept = rememberSaveableStateHolder()
    val opened = open?.let { id -> entries.firstOrNull { it.id == id } }
    if (opened != null) {
        EntryDetail(opened, ToolContext(tool.snackbar, onBack = { open = null }, tool.onOpenSetup))
        return
    }
    kept.SaveableStateProvider(LIST) { Timeline(tool, entries) { open = it } }
}

@Composable
private fun Timeline(
    tool: ToolContext,
    entries: List<ReactotronTimeline.Entry>,
    onOpen: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = kindName?.let(ReactotronKind::valueOf)
    val visible =
        remember(entries, kind, query) {
            entries.filter { ReactotronLabels.matches(it, kind, query) }.asReversed()
        }
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    BackHandler(onBack = tool.onBack)
    Scaffold(snackbarHost = { SnackbarHost(tool.snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolTitle("Reactotron", tool)
                    Box(Modifier.weight(1f))
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = Reactotron::clear) {
                            Icon(
                                painterResource(Symbols.byName.getValue("delete_sweep").outlined),
                                "Clear",
                            )
                        }
                    }
                }
            }
            item { ServerSection() }
            item {
                Filters(kind, query, onKind = { kindName = it?.name }, onQuery = { query = it })
            }
            item {
                ToolNote(
                    when {
                        entries.isEmpty() && clients.isEmpty() -> "Nothing yet."
                        entries.isEmpty() -> "Connected. What the app says shows here."
                        else -> "${visible.size} of ${entries.size}"
                    }
                )
            }
            val many = clients.values.map { it.name }.distinct().size > 1
            items(visible, key = { it.id }) { entry -> EntryRow(entry, many) { onOpen(entry.id) } }
        }
    }
}

/** Start and stop, the apps connected now, and how to point one here. */
@Composable
private fun ServerSection() {
    val context = LocalContext.current
    val state by Reactotron.state.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    ToolSection("Server") {
        when (val now = state) {
            is Reactotron.State.Listening -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Listening on 127.0.0.1:${now.port}", Modifier.weight(1f))
                    OutlinedButton(onClick = { Reactotron.stop(context) }) { Text("Stop") }
                }
                if (clients.isEmpty()) {
                    ToolNote("No app yet. Open yours, or reload it, and it connects.")
                } else {
                    clients.values.forEach { client ->
                        InfoRow(
                            "Connected",
                            listOfNotNull(client.name, client.platform).joinToString(" · "),
                        )
                    }
                }
            }
            is Reactotron.State.Failed -> {
                ToolNote(now.why)
                if (now.taken) {
                    InfoRow("Free it", REVERSE_REMOVE, copyable = true, mono = true)
                }
                Button(onClick = { Reactotron.start(context) }) { Text("Try again") }
            }
            Reactotron.State.Off -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Off", Modifier.weight(1f))
                    Button(onClick = { Reactotron.start(context) }) { Text("Start") }
                }
            }
        }
        ToolNote(
            "A React Native app's Reactotron connects to localhost:9090. With no adb reverse " +
                "from a computer, that is this phone, so the debug build you are running reports " +
                "here: no host to set, no cable."
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(
    kind: ReactotronKind?,
    query: String,
    onKind: (ReactotronKind?) -> Unit,
    onQuery: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == null, onClick = { onKind(null) }, label = { Text("All") })
            ReactotronKind.entries.forEach { each ->
                FilterChip(
                    selected = kind == each,
                    onClick = { onKind(if (kind == each) null else each) },
                    label = { Text(each.title) },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(entry: ReactotronTimeline.Entry, showApp: Boolean, onClick: () -> Unit) {
    val event = entry.event
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(TAG_WIDTH.dp)) {
            Text(
                ReactotronLabels.tag(event),
                color = tagColor(event),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                TIME.format(Instant.ofEpochMilli(entry.at)),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                ReactotronLabels.summary(event),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (showApp && entry.app != null) {
                Text(
                    entry.app.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun tagColor(event: ReactotronEvent): Color =
    when {
        event is ReactotronEvent.Log && event.level == ReactotronEvent.Log.Level.Error ->
            MaterialTheme.colorScheme.error
        event is ReactotronEvent.Log && event.level == ReactotronEvent.Log.Level.Warn ->
            MaterialTheme.colorScheme.tertiary
        event is ReactotronEvent.ApiResponse && (event.status == 0 || event.status >= 400) ->
            MaterialTheme.colorScheme.error
        event is ReactotronEvent.Unknown && event.type == ReactotronTimeline.GONE ->
            MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }

/** One entry, whole: what it was, and everything it carried. */
@Composable
private fun EntryDetail(entry: ReactotronTimeline.Entry, tool: ToolContext) {
    val context = LocalContext.current
    val say = rememberSay(tool)
    val event = entry.event
    ToolScaffold(
        ReactotronLabels.tag(event).lowercase().replaceFirstChar { it.uppercase() },
        tool,
    ) {
        Text(ReactotronLabels.summary(event), style = MaterialTheme.typography.bodyMedium)
        ToolSection("Overview") {
            entry.app?.let { InfoRow("App", it) }
            InfoRow("Received", TIME.format(Instant.ofEpochMilli(entry.at)))
            InfoRow("Type", entry.command.type, mono = true)
            if (entry.size > 0) InfoRow("Size", "${entry.size} bytes")
            if (entry.command.important) InfoRow("Important", "Yes")
        }
        when (event) {
            is ReactotronEvent.ApiResponse -> ApiDetail(event)
            is ReactotronEvent.Log -> LogDetail(event, entry)
            else -> Payload("Payload", entry.command.payload)
        }
        val whole = entry.command.payload?.let(::pretty)
        if (whole != null) {
            OutlinedButton(
                onClick = {
                    context.copy("Reactotron payload", whole)
                    say("Copied the payload")
                }
            ) {
                Text("Copy payload")
            }
        }
    }
}

@Composable
private fun ApiDetail(event: ReactotronEvent.ApiResponse) {
    ToolSection("Request") {
        InfoRow("URL", "${event.method} ${event.url}", copyable = true, mono = true)
        InfoRow("Took", ReactotronLabels.millis(event.durationMillis))
        Headers(event.request.child("headers"))
    }
    Payload("Request body", event.request.child("data"))
    ToolSection("Response") {
        InfoRow(
            "Status",
            if (event.status == 0) "No answer: a network error" else "${event.status}",
        )
        Headers(event.response.child("headers"))
    }
    Payload("Response body", event.response.child("body"))
}

@Composable
private fun LogDetail(event: ReactotronEvent.Log, entry: ReactotronTimeline.Entry) {
    Payload("Message", entry.command.payload.child("message"))
    if (event.stack.isNotEmpty()) {
        ToolSection("Stack") {
            event.stack.forEach { frame ->
                val at = listOfNotNull(frame.line, frame.column).joinToString(":")
                InfoRow(
                    frame.functionName.ifEmpty { "(anonymous)" },
                    "${frame.fileName} $at",
                    mono = true,
                )
            }
        }
    }
}

@Composable
private fun Headers(headers: JsonElement?) {
    val fields = (headers as? JsonObject).orEmpty()
    if (fields.isEmpty()) ToolNote("No headers.")
    fields.forEach { (name, value) ->
        InfoRow(name, (value as? JsonPrimitive)?.content ?: value.toString(), mono = true)
    }
}

/** A part of the payload, laid out as JSON — text that is itself JSON, as a body often is, too. */
@Composable
private fun Payload(title: String, value: JsonElement?) {
    if (value == null || value == JsonNull) return
    val shown = remember(value) { pretty(value) }
    ToolSection(title) {
        Text(
            shown.take(MAX_SHOWN),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        if (shown.length > MAX_SHOWN)
            ToolNote("Showing the first ${MAX_SHOWN / 1_000}k characters.")
    }
}

private fun pretty(value: JsonElement): String {
    // An API body arrives as a string more often than not; one that is JSON reads as JSON.
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    val parsed = text?.let {
        try {
            Json.parseToJsonElement(it)
        } catch (_: SerializationException) {
            null
        }
    }
    if (text != null && (parsed == null || parsed is JsonPrimitive)) return text
    return PRETTY.encodeToString(JsonElement.serializer(), parsed ?: value)
}

private fun JsonElement?.child(name: String): JsonElement? = (this as? JsonObject)?.get(name)

private val PRETTY = Json { prettyPrint = true }
private const val LIST = "list"
private const val PACE_MILLIS = 250L
private const val TAG_WIDTH = 92
private const val MAX_SHOWN = 100_000
private const val REVERSE_REMOVE = "adb reverse --remove tcp:9090"
