package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidective.android.proxy.Hold
import com.droidective.android.proxy.Holds
import com.droidective.core.net.http.Headers
import com.droidective.core.net.proxy.Breakpoint
import com.droidective.core.net.proxy.FlowLabels
import com.droidective.core.net.proxy.MockDraft
import com.droidective.core.net.proxy.Origin
import com.droidective.core.net.proxy.RequestDraft
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What is held right now, at the top of the screen where it can't be missed. */
@Composable
internal fun HeldCard(holds: List<Hold>, onOpen: (Hold) -> Unit) {
    ToolSection("Held at a breakpoint") {
        ToolNote(
            "Each waits for you to let it go, change it or stop it. After " +
                "${Holds.WAIT_MINUTES} minutes it goes on as it was."
        )
        holds.forEach { hold ->
            val flow = hold.flow
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(hold) }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${flow.request.method} ${flow.authority}${FlowLabels.path(flow)}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (hold is Hold.Response) "Its answer, waiting to reach the app"
                        else "Waiting to go to the server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { Holds.go(flow.id) }) { Text("Let it go") }
            }
        }
    }
}

/**
 * One held exchange, open to change: a request's method, URL, headers and body, or an answer's
 * status, headers and body. Leaving without a decision leaves it held.
 */
@Composable
fun HeldEditor(hold: Hold, tool: ToolContext) {
    when (hold) {
        is Hold.Request -> HeldRequestEditor(hold, tool)
        is Hold.Response -> HeldResponseEditor(hold, tool)
    }
}

@Composable
private fun HeldRequestEditor(hold: Hold.Request, tool: ToolContext) {
    val flow = hold.flow
    val text = remember(hold) { utf8(hold.held.body) }
    var method by rememberSaveable { mutableStateOf(hold.held.head.method) }
    var url by rememberSaveable { mutableStateOf(flow.url) }
    var headers by rememberSaveable {
        // The held body is as the app sent it, so its Content-Encoding still describes it.
        mutableStateOf(shown(hold.held.head.headers, Headers.CONTENT_LENGTH, Headers.HOST))
    }
    var body by rememberSaveable { mutableStateOf(text.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    ToolScaffold("Held request", tool) {
        Field("Method", method) { method = it }
        Field("URL", url) { url = it }
        HeadersField(headers) { headers = it }
        if (text != null) BodyField(body) { body = it }
        else ToolNote("The body isn't text, so it goes as it was, whatever else changes.")
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Verdicts(
            hold,
            tool,
            onSend = {
                val draft = RequestDraft(method, url, headers, if (text != null) body else "")
                when (val built = draft.build()) {
                    is RequestDraft.Built.Invalid -> problem = built.reason
                    is RequestDraft.Built.Ready -> {
                        val out = built.outgoing
                        if (
                            out.secure != flow.secure || out.origin != Origin(flow.host, flow.port)
                        ) {
                            problem =
                                "The host can't change here: the app is talking to this one. " +
                                    "Edit and send, on the request in the list, goes anywhere."
                        } else {
                            // The connection stays as the app left it; a draft asks to close it.
                            val head =
                                out.head.with(
                                    headers = out.head.headers.without(*Headers.HOP_BY_HOP)
                                )
                            Holds.send(
                                flow.id,
                                head,
                                if (text != null) out.body else hold.held.body,
                            )
                            tool.onBack()
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun HeldResponseEditor(hold: Hold.Response, tool: ToolContext) {
    val flow = hold.flow
    val head = hold.held.head
    // Decoded on the way in when the proxy could. One that still isn't text may be compressed in
    // a way it can't read, and written back as plain text it would arrive as nonsense.
    val text = remember(hold) { utf8(hold.held.body) }
    var status by rememberSaveable { mutableStateOf(head.status.toString()) }
    var headers by rememberSaveable {
        mutableStateOf(shown(head.headers, Headers.CONTENT_LENGTH, Headers.CONTENT_ENCODING))
    }
    var body by rememberSaveable { mutableStateOf(text.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    ToolScaffold("Held answer", tool) {
        Text(
            "${flow.request.method} ${flow.url}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        if (text == null) {
            val why =
                hold.held.compressed?.let { "came $it-compressed, which this can't read" }
                    ?: "isn't text"
            ToolNote("The answer $why, so it can go on as it came or be stopped.")
            Verdicts(hold, tool, onSend = null)
            return@ToolScaffold
        }
        OutlinedTextField(
            value = status,
            onValueChange = { status = it },
            label = { Text("Status") },
            singleLine = true,
            textStyle = MONO,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        HeadersField(headers) { headers = it }
        BodyField(body) { body = it }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Verdicts(
            hold,
            tool,
            onSend = {
                // An answer written by hand is what a mock is: the same checks, the same length.
                val draft = MockDraft(flow.url, "", status, headers, body, enabled = true)
                when (val built = draft.build(id = "")) {
                    is MockDraft.Built.Invalid -> problem = built.reason
                    is MockDraft.Built.Ready -> {
                        val (answer, bytes) = built.mock.response()
                        val reason =
                            if (answer.status == head.status) head.reason else answer.reason
                        Holds.answer(flow.id, answer.copy(reason = reason), bytes)
                        tool.onBack()
                    }
                }
            },
        )
    }
}

/** Let it go, send it changed when it can be, or stop it; each ends the hold and goes back. */
@Composable
private fun Verdicts(hold: Hold, tool: ToolContext, onSend: (() -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                Holds.go(hold.flow.id)
                tool.onBack()
            }
        ) {
            Text("Let it go")
        }
        if (onSend != null) OutlinedButton(onClick = onSend) { Text("Send changed") }
        OutlinedButton(
            onClick = {
                Holds.stop(hold.flow.id)
                tool.onBack()
            }
        ) {
            Text("Stop")
        }
    }
}

/** A breakpoint, open to change: which requests it holds, and whether their answers too. */
@Composable
fun BreakpointEditor(start: Breakpoint, isNew: Boolean, tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(start.url) }
    var method by rememberSaveable { mutableStateOf(start.method.orEmpty()) }
    var requests by rememberSaveable { mutableStateOf(start.requests) }
    var responses by rememberSaveable { mutableStateOf(start.responses) }
    var enabled by rememberSaveable { mutableStateOf(start.enabled) }
    var problem by remember { mutableStateOf<String?>(null) }
    ToolScaffold(if (isNew) "New breakpoint" else "Breakpoint", tool) {
        ToolNote(
            "Matching requests wait for you before they go, or their answers before they reach " +
                "the app. * stands for anything, and the query isn't matched."
        )
        Field("URL", url) { url = it }
        Field("Method, or any", method) { method = it }
        SwitchRow("Hold the request", requests) { requests = it }
        SwitchRow("Hold the answer", responses) { responses = it }
        SwitchRow("On", enabled) { enabled = it }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val point =
                        start.copy(
                            url = url.trim(),
                            method = method.trim().uppercase().ifEmpty { null },
                            requests = requests,
                            responses = responses,
                            enabled = enabled,
                        )
                    problem = point.problem()
                    if (problem == null) {
                        scope.launch(Dispatchers.IO) { Holds.save(context, point) }
                        tool.onBack()
                    }
                }
            ) {
                Text("Save")
            }
            if (!isNew) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) { Holds.remove(context, start.id) }
                        tool.onBack()
                    }
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

/** A list row: what a breakpoint holds, whether it's on, and a tap to edit it. */
@Composable
internal fun BreakpointRow(point: Breakpoint, onEdit: () -> Unit, onEnable: (Boolean) -> Unit) {
    val holds =
        listOfNotNull("requests".takeIf { point.requests }, "answers".takeIf { point.responses })
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                point.url,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${point.method ?: "Any method"} · holds ${holds.joinToString(" and ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = point.enabled, onCheckedChange = onEnable)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MONO,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HeadersField(headers: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = headers,
        onValueChange = onChange,
        label = { Text("Headers, one Name: value to a line") },
        textStyle = MONO,
        modifier = Modifier.fillMaxWidth().heightIn(min = FIELD_DP.dp),
    )
}

@Composable
private fun BodyField(body: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = body,
        onValueChange = onChange,
        label = { Text("Body") },
        textStyle = MONO,
        modifier = Modifier.fillMaxWidth().heightIn(min = FIELD_DP.dp),
    )
}

@Composable
private fun SwitchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Headers as they're edited: without the ones that only say how the body travels. */
private fun shown(headers: Headers, vararg worked: String): String =
    headers.without(*worked, *Headers.HOP_BY_HOP).joinToString("\n") { "${it.name}: ${it.value}" }

private fun utf8(bytes: ByteArray): String? =
    try {
        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

private val MONO = TextStyle(fontFamily = FontFamily.Monospace)
private const val FIELD_DP = 120
