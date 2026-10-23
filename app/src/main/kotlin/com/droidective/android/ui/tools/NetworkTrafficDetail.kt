package com.droidective.android.ui.tools

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.proxy.ProtoFolder
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.copy
import com.droidective.core.net.grpc.Grpc
import com.droidective.core.net.grpc.ProtoSchema
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import com.droidective.core.net.proxy.BodyShape
import com.droidective.core.net.proxy.BodyView
import com.droidective.core.net.proxy.Breakpoint
import com.droidective.core.net.proxy.Curl
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.FlowLabels
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.Remap
import com.droidective.core.net.proxy.Repeat
import com.droidective.core.net.proxy.Repeater
import com.droidective.core.net.proxy.RequestDraft
import com.droidective.core.net.proxy.TlsFailure
import com.droidective.core.net.proxy.TrafficRules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One exchange: where it went, what was sent, what came back, and a way to send it again. */
@Composable
fun FlowDetail(
    flow: Flow,
    tool: ToolContext,
    onOpen: (Long) -> Unit,
    onMock: (Mock) -> Unit,
    onBreak: (Breakpoint) -> Unit,
    onRemap: (Remap) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val say = rememberSay(tool)
    var sending by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val draft = remember(flow) { RequestDraft.of(flow) }
    if (editing && draft != null) {
        RequestEditor(
            flow,
            draft,
            ToolContext(tool.snackbar, onBack = { editing = false }, tool.onOpenSetup),
            onSent = {
                editing = false
                onOpen(it)
            },
        )
        return
    }
    val labels = remember { AppLabels(context) }
    // A gRPC call's bodies read with the app's own schema, when the protos folder has it.
    var schemasKept by remember { mutableIntStateOf(0) }
    val ask = rememberServerAsk(flow, say) { schemasKept++ }
    val schemas by
        produceState(Schemas.NONE, flow.id, schemasKept) {
            if (FlowLabels.grpcMethod(flow) != null) {
                value = withContext(Dispatchers.IO) { Schemas.of(ProtoFolder(context)) }
            }
        }
    // A socket opens on what it said: its request and its 101 are the least of it.
    var side by rememberSaveable {
        mutableIntStateOf(
            when {
                flow.socket -> MESSAGES
                flow.response != null -> 1
                else -> 0
            }
        )
    }
    ToolScaffold(if (flow.opening) flow.host else "${flow.request.method} ${flow.host}", tool) {
        Text(
            flow.url,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier.clickable {
                    context.copy("URL", flow.url)
                    say("Copied the URL")
                },
        )
        ToolSection("Overview") {
            InfoRow("Status", FlowLabels.status(flow))
            FlowLabels.grpc(flow)?.let { InfoRow("gRPC", it.toString()) }
            flow.app?.let { InfoRow("App", "${labels.of(it)}\n$it") }
            InfoRow("Started", TIME.format(Instant.ofEpochMilli(flow.startedAt)))
            flow.durationMillis?.let { InfoRow("Took", FlowLabels.duration(it)) }
            if (flow.wireLength > 0) InfoRow("Moved", FlowLabels.size(flow.wireLength))
            InfoRow("Connection", connection(flow))
            flow.repeatOf?.let { InfoRow("Repeat of", "#$it") }
            if (flow.mocked != null) InfoRow("Answered by", "A mock, not the server")
            flow.sentTo?.let { InfoRow("Sent to", "$it, by a remap") }
            if (flow.paused != null) {
                InfoRow("Breakpoint", if (flow.edited) "Held, and sent on changed" else "Held")
            }
        }
        flow.failure?.let { FailureSection(it) }
        if (!flow.tunnelled || flow.response != null) {
            val sides = listOfNotNull("Request", "Response", "Messages".takeIf { flow.socket })
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sides.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = side == index,
                        onClick = { side = index },
                        shape = SegmentedButtonDefaults.itemShape(index, sides.size),
                    ) {
                        Text(label)
                    }
                }
            }
            if (side == MESSAGES && flow.socket) {
                SocketMessages(flow.id)
            } else if (side == 0) {
                Message(
                    flow.request.headers,
                    flow.requestBody,
                    "request",
                    reading = schemas.reading(flow, answer = false, ask),
                )
            } else {
                val response = flow.response
                if (response == null) {
                    ToolNote(if (flow.failure != null) "Nothing came back." else "Waiting…")
                } else {
                    Message(
                        response.headers,
                        flow.responseBody,
                        "response",
                        flow.trailers,
                        schemas.reading(flow, answer = true, ask),
                    )
                }
            }
        }
        val repeat = remember(flow) { Repeater.prepare(flow) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    sending = true
                    scope.launch {
                        val sent = TrafficProxy.repeat(context, flow)
                        sending = false
                        // The repeat is a flow of its own, which is where to look next.
                        sent?.let { onOpen(it.id) }
                    }
                },
                enabled = repeat is Repeat.Ready && !sending,
            ) {
                Text(if (sending) "Sending…" else "Send again")
            }
            OutlinedButton(onClick = { editing = true }, enabled = draft != null) {
                Text("Edit and send")
            }
        }
        val mock = remember(flow) { Mock.of(flow, UUID.randomUUID().toString()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    context.copy("cURL", Curl.of(flow))
                    say("Copied as cURL")
                },
                enabled = !flow.opening,
            ) {
                Text("Copy as cURL")
            }
            // Its answer, kept to give back the next time, and changed if need be.
            OutlinedButton(onClick = { mock?.let(onMock) }, enabled = mock != null) {
                Text("Mock this response")
            }
        }
        if (mock == null && !flow.opening) Mock.whyNot(flow)?.let { ToolNote(it) }
        // Neither reaches an HTTP/2 connection, which Mock.whyNot has already said.
        if (!flow.opening && !flow.tunnelled && !flow.http2) {
            OutlinedButton(
                onClick = { onBreak(Breakpoint.of(flow, UUID.randomUUID().toString())) }
            ) {
                Text("Hold requests like this")
            }
            OutlinedButton(onClick = { onRemap(Remap.of(flow, UUID.randomUUID().toString())) }) {
                Text("Send this server's requests elsewhere")
            }
        }
        (repeat as? Repeat.Refused)?.let { ToolNote(it.reason) }
        RuleShortcuts(flow, labels, say)
    }
}

/** The two rules most often made while looking at one flow, a tap away from it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleShortcuts(flow: Flow, labels: AppLabels, say: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { TrafficProxy.loadRules(context) } }
    val rules by TrafficProxy.rules.collectAsStateWithLifecycle()
    val host = TrafficRules.pattern(flow.host)
    val app = flow.app
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (host != null && host !in rules.passThrough) {
            OutlinedButton(
                onClick = {
                    scope.saveRules(context, rules.passingThrough(host))
                    say("$host is passed through from now on")
                }
            ) {
                Text("Pass $host through")
            }
        }
        if (app != null && rules.apps != listOf(app)) {
            OutlinedButton(
                onClick = {
                    scope.saveRules(context, rules.copy(apps = listOf(app)))
                    say("Reading only ${labels.of(app)} from now on")
                }
            ) {
                Text("Read only ${labels.of(app)}")
            }
        }
    }
}

private fun connection(flow: Flow): String =
    when {
        flow.repeatOf != null && flow.http2 -> "HTTP/2, sent again by Droidective"
        flow.repeatOf != null && flow.secure -> "HTTPS, sent again by Droidective"
        flow.repeatOf != null -> "HTTP, sent again by Droidective"
        flow.tunnelled && flow.response == null ->
            FlowLabels.passed(flow)?.replaceFirstChar { it.uppercase() } ?: "Passed through"
        flow.socket && flow.secure -> "A secure WebSocket, read through the proxy's certificate"
        flow.socket -> "A WebSocket, read as it talks"
        flow.tunnelled -> "Upgraded, then carried as bytes"
        flow.http2 -> "HTTP/2, read through the proxy's certificate"
        flow.secure -> "HTTPS, read through the proxy's certificate"
        else -> "HTTP"
    }

/** What went wrong, and for the commonest failure what to do about it. */
@Composable
private fun FailureSection(failure: String) {
    ToolSection("What happened") {
        Text(failure, color = MaterialTheme.colorScheme.error)
        if (failure.startsWith(TlsFailure.REFUSED)) {
            ToolNote(
                "The app wouldn't accept Droidective's certificate. Either the certificate isn't " +
                    "installed, the app doesn't trust user certificates (most apps don't, unless " +
                    "it's your own debug build set up to), or it pins its certificates."
            )
        }
    }
}

/**
 * The schemas folder as one flow's detail reads it: the schema, and where to put one when it hasn't
 * the call's types.
 */
private class Schemas(val schema: ProtoSchema?, val folder: String?) {
    /**
     * How [flow]'s request, or its answer, is read: with its type, or with a word on finding it.
     */
    fun reading(flow: Flow, answer: Boolean, ask: ServerAsk): Reading? {
        if (FlowLabels.grpcMethod(flow) == null) return null
        return Reading(schema, schema?.typed(flow.request.path, answer), folder, ask)
    }

    companion object {
        val NONE = Schemas(null, null)

        fun of(folder: ProtoFolder) = Schemas(folder.schema(), folder.folder()?.path)
    }
}

/**
 * A gRPC body's reading: the schema, the message type it is in it, the folder for one, and asking
 * the server for one.
 */
internal class Reading(
    val schema: ProtoSchema?,
    val typed: ProtoSchema.Typed?,
    val folder: String?,
    val ask: ServerAsk? = null,
)

/** A message's headers, its body decoded, and the trailers after it when there were any. */
@Composable
internal fun Message(
    headers: Headers,
    body: CapturedBody?,
    what: String,
    trailers: Headers = Headers(),
    reading: Reading? = null,
) {
    val context = LocalContext.current
    ToolSection("Headers") {
        if (headers.all.isEmpty()) ToolNote("None.")
        headers.forEach { header -> HeaderValue(header, reading?.schema) }
    }
    val shape by
        produceState<BodyShape?>(null, headers, body, reading?.typed) {
            value = withContext(Dispatchers.Default) { BodyView.of(headers, body, reading?.typed) }
        }
    ToolSection("Body") {
        when (val shown = shape) {
            null -> ToolNote("Reading…")
            BodyShape.Empty -> ToolNote("No body.")
            is BodyShape.Json -> TextBody(shown.pretty, what)
            is BodyShape.Text -> TextBody(shown.text, what)
            is BodyShape.Form -> shown.fields.forEach { (name, value) -> NameValue(name, value) }
            is BodyShape.Image -> ImageBody(shown)
            is BodyShape.Grpc -> GrpcBody(shown, what, reading)
            is BodyShape.Binary ->
                ToolNote("${FlowLabels.size(shown.bytes.size.toLong())}, ${shown.reason}.")
        }
        if (body?.truncated == true) {
            ToolNote(
                "Only the first ${FlowLabels.size(body.bytes.size.toLong())} of " +
                    "${FlowLabels.size(body.wireLength)} was kept. The app got all of it."
            )
        }
        if (shape is BodyShape.Json || shape is BodyShape.Text || shape is BodyShape.Grpc) {
            OutlinedButton(
                onClick = {
                    val text =
                        when (val shown = shape) {
                            is BodyShape.Json -> shown.pretty
                            is BodyShape.Text -> shown.text
                            is BodyShape.Grpc -> shown.text
                            else -> ""
                        }
                    context.copy("The $what body", text)
                }
            ) {
                Text("Copy body")
            }
        }
    }
    if (trailers.all.isNotEmpty()) {
        ToolSection("Trailers") { trailers.forEach { HeaderValue(it, reading?.schema) } }
    }
}

/**
 * A gRPC body's messages, each with its fields read without the schema. A stream can carry
 * thousands, so only the first few are laid out; copying the body takes them all.
 */
@Composable
private fun GrpcBody(body: BodyShape.Grpc, what: String, reading: Reading?) {
    if (body.messages.isEmpty()) ToolNote("No messages.")
    body.messages.take(MAX_SHOWN_MESSAGES).forEachIndexed { index, message ->
        val compressed = if (message.compressed) ", compressed" else ""
        val type = message.type?.let { " · $it" }.orEmpty()
        Text(
            "Message ${index + 1} · ${FlowLabels.size(message.wireLength.toLong())}$compressed$type",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fields = message.fields
        val text = message.text
        when {
            message.problem != null -> ToolNote("Not shown: ${message.problem}.")
            fields == "" -> ToolNote("Empty: every field at its default.")
            fields != null -> TextBody(fields, what)
            text != null -> TextBody(text, what)
        }
    }
    val rest = body.messages.size - MAX_SHOWN_MESSAGES
    if (rest > 0) ToolNote("And $rest more. Copy the body for all of them.")
    if (reading != null && reading.typed == null && body.messages.isNotEmpty()) {
        SchemaNote(reading)
    }
}

/** Long bodies are cut on screen, not in what is copied: laying out megabytes stalls a phone. */
@Composable
private fun TextBody(text: String, what: String) {
    val shown = if (text.length > MAX_SHOWN_CHARS) text.take(MAX_SHOWN_CHARS) else text
    Text(
        shown,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(4.dp),
    )
    if (shown.length < text.length) {
        ToolNote(
            "Showing the first ${MAX_SHOWN_CHARS / 1_000}k characters of the $what body. Copy " +
                "it for all of it."
        )
    }
}

/** A header, a binary one read as the protobuf it carries rather than shown as base64. */
@Composable
private fun HeaderValue(header: Header, schema: ProtoSchema?) {
    val read = remember(header, schema) { Grpc.binaryHeader(header.name, header.value, schema) }
    NameValue(header.name, read?.trimEnd() ?: header.value)
}

/** A header or a form field: the name small above the value, copied as `name: value` on a tap. */
@Composable
private fun NameValue(name: String, value: String) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth()
            .clickable { context.copy(name, "$name: $value") }
            .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ImageBody(image: BodyShape.Image) {
    val bitmap =
        remember(image) {
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)?.asImageBitmap()
        }
    if (bitmap == null) {
        ToolNote("${image.mediaType}, ${FlowLabels.size(image.bytes.size.toLong())}.")
    } else {
        Image(
            bitmap,
            contentDescription = image.mediaType,
            modifier = Modifier.heightIn(max = MAX_IMAGE_HEIGHT.dp),
        )
        ToolNote("${image.mediaType}, ${bitmap.width} × ${bitmap.height}")
    }
}

private const val MAX_SHOWN_CHARS = 100_000
private const val MAX_SHOWN_MESSAGES = 50
private const val MAX_IMAGE_HEIGHT = 320
internal val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private const val MESSAGES = 2
