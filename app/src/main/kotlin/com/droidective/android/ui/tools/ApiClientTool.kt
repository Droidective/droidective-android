package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.api.ApiRequests
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.copy
import com.droidective.core.api.CurlCommand
import com.droidective.core.api.SavedRequest
import com.droidective.core.api.Variable
import com.droidective.core.net.proxy.Curl
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.FlowLabels
import com.droidective.core.net.proxy.RequestDraft
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Requests written by hand, kept by name, and sent from the phone: on its network, through its VPN,
 * to a server only it can reach. `{{name}}` takes a value kept once, and a cURL command — what a
 * browser copies — pastes straight in.
 */
@Composable
fun ApiClientTool(tool: ToolContext) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ApiRequests.requests.load(context)
            ApiRequests.variables.load(context)
        }
    }
    val saved by ApiRequests.requests.items.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<OpenRequest?>(null) }
    var pasting by remember { mutableStateOf(false) }
    editing?.let { now ->
        val back = ToolContext(tool.snackbar, onBack = { editing = null }, tool.onOpenSetup)
        key(now.request.id) { RequestScreen(now.request, now.isNew, now.note, back) }
        return
    }
    ToolScaffold("API Testing", tool) {
        ToolNote(
            "Send requests from the phone, with its network and its VPN. Each one also shows in " +
                "Network Traffic, and goes where a remap there sends it."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { editing = OpenRequest(SavedRequest(newId(), "New request"), true) }
            ) {
                Text("New request")
            }
            OutlinedButton(onClick = { pasting = true }) { Text("Paste cURL") }
        }
        ToolSection("Saved") {
            if (saved.isEmpty()) ToolNote("None yet. A request you save shows here.")
            saved.forEach { request ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { editing = OpenRequest(request, false) }
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                ) {
                    Text(request.name)
                    Text(
                        "${request.method} ${request.url}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        VariablesSection()
    }
    if (pasting) {
        PasteDialog(onDismiss = { pasting = false }) { draft, skipped ->
            pasting = false
            val name = draft.url.substringAfter("://").substringBefore('?')
            val note =
                skipped.takeIf { it.isNotEmpty() }?.let { "Left out: ${it.joinToString(" ")}" }
            editing = OpenRequest(SavedRequest.of(newId(), name, draft), true, note)
        }
    }
}

/** A request open in the editor, whether saving it makes a new one, and what to say about it. */
private class OpenRequest(val request: SavedRequest, val isNew: Boolean, val note: String? = null)

/** One request: its fields, Send, and what came back. */
@Composable
private fun RequestScreen(start: SavedRequest, isNew: Boolean, note: String?, tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val values by ApiRequests.variables.items.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(start.name) }
    var method by rememberSaveable { mutableStateOf(start.method) }
    var url by rememberSaveable { mutableStateOf(start.url) }
    var headers by rememberSaveable { mutableStateOf(start.headers) }
    var body by rememberSaveable { mutableStateOf(start.body) }
    var problem by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<Flow?>(null) }
    val current = {
        start.copy(name = name.trim(), method = method, url = url, headers = headers, body = body)
    }
    ToolScaffold(if (isNew) "New request" else start.name, tool) {
        note?.let { ToolNote(it) }
        Field("Name", name, Modifier.fillMaxWidth()) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Method", method, Modifier.weight(METHOD_WEIGHT)) { method = it }
            Field("URL", url, Modifier.weight(1f)) { url = it }
        }
        BigField("Headers, one Name: value to a line", headers) { headers = it }
        BigField("Body", body) { body = it }
        ToolNote("{{name}} takes a variable's value. Host and Content-Length are worked out.")
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !sending,
                onClick = {
                    val request = current()
                    val known = values.associate { it.name to it.value }
                    val missing = request.missing(known)
                    if (missing.isNotEmpty()) {
                        problem = "No value for ${missing.joinToString { "{{$it}}" }}."
                        return@Button
                    }
                    when (val built = request.draft(known).build()) {
                        is RequestDraft.Built.Invalid -> problem = built.reason
                        is RequestDraft.Built.Ready -> {
                            problem = null
                            sending = true
                            scope.launch {
                                answer = TrafficProxy.send(context, built.outgoing)
                                sending = false
                            }
                        }
                    }
                },
            ) {
                Text(if (sending) "Sending…" else "Send")
            }
            OutlinedButton(
                onClick = {
                    val request = current()
                    if (request.name.isEmpty()) {
                        problem = "Give it a name to keep it by."
                        return@OutlinedButton
                    }
                    scope.launch(Dispatchers.IO) { ApiRequests.requests.save(context, request) }
                    scope.launch { tool.say("Saved as ${request.name}") }
                }
            ) {
                Text("Save")
            }
            if (!isNew) {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            ApiRequests.requests.remove(context, start.id)
                        }
                        tool.onBack()
                    }
                ) {
                    Text("Delete")
                }
            }
        }
        answer?.let { Answer(it) }
    }
}

/** What came back: the status, how long, how much, and the message itself. */
@Composable
private fun Answer(flow: Flow) {
    val context = LocalContext.current
    ToolSection("Answer") {
        InfoRow("Status", FlowLabels.status(flow))
        flow.durationMillis?.let { InfoRow("Took", FlowLabels.duration(it)) }
        if (flow.wireLength > 0) InfoRow("Moved", FlowLabels.size(flow.wireLength))
        flow.sentTo?.let { InfoRow("Sent to", "$it, by a remap") }
        flow.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { context.copy("cURL", Curl.of(flow)) }) { Text("Copy as cURL") }
    }
    val response = flow.response ?: return
    Message(response.headers, flow.responseBody, "response")
}

/** The values `{{names}}` take, each to change or remove, and a way to add one. */
@Composable
private fun VariablesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val values by ApiRequests.variables.items.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf("") }
    val save = { variable: Variable ->
        scope.launch(Dispatchers.IO) { ApiRequests.variables.save(context, variable) }
    }
    ToolSection("Variables") {
        if (values.isEmpty())
            ToolNote("None yet. A request's {{host}} or {{token}} takes its value here.")
        values.forEach { variable ->
            // Its own text, so typing never waits on the file it is saved to.
            var text by rememberSaveable(variable.name) { mutableStateOf(variable.value) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field("{{${variable.name}}}", text, Modifier.weight(1f)) {
                    text = it
                    save(variable.copy(value = it))
                }
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            ApiRequests.variables.remove(context, variable.name)
                        }
                    }
                ) {
                    Text("Remove")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("New variable's name", adding, Modifier.weight(1f)) { adding = it }
            TextButton(
                enabled = NAME.matches(adding.trim()) && values.none { it.name == adding.trim() },
                onClick = {
                    save(Variable(adding.trim(), ""))
                    adding = ""
                },
            ) {
                Text("Add")
            }
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onRead: (RequestDraft, List<String>) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a cURL command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = { Text("curl 'https://…' -H '…'") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = FIELD_DP.dp),
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = command.isNotBlank(),
                onClick = {
                    when (val parsed = CurlCommand.parse(command.trim())) {
                        is CurlCommand.Parsed.Invalid -> problem = parsed.reason
                        is CurlCommand.Parsed.Ready -> onRead(parsed.draft, parsed.skipped)
                    }
                },
            ) {
                Text("Read it")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        modifier = modifier,
    )
}

@Composable
private fun BigField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().heightIn(min = FIELD_DP.dp),
    )
}

private fun newId() = UUID.randomUUID().toString()

/** What `{{name}}` can name. */
private val NAME = Regex("[A-Za-z_][A-Za-z0-9_.\\-]*")

private const val METHOD_WEIGHT = 0.35f
private const val FIELD_DP = 100
