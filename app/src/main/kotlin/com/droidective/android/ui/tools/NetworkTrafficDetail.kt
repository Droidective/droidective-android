package com.droidective.android.ui.tools

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.proxy.ProtoFolder
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.Symbols
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
import com.droidective.core.net.proxy.FlowOutcome
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.Remap
import com.droidective.core.net.proxy.Repeat
import com.droidective.core.net.proxy.Repeater
import com.droidective.core.net.proxy.RequestDraft
import com.droidective.core.net.proxy.TlsFailure
import com.droidective.core.net.proxy.TrafficRules
import com.droidective.core.net.proxy.Unread
import com.droidective.core.reactotron.ReactotronFind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a request's detail can open: another request, or an editor for a rule made from it. */
internal class FlowActions(
    val onOpen: (Long) -> Unit,
    val onMock: (Mock) -> Unit,
    val onBreak: (Breakpoint) -> Unit,
    val onRemap: (Remap) -> Unit,
)

/**
 * One exchange: its status, then what came back (or what was sent), the body before the headers,
 * then sending it again; the rest of what can be done with it is in the menu. With [onBack] it has
 * a back arrow; without, it sits beside the list.
 */
@Composable
internal fun FlowDetail(
    flow: Flow,
    tool: ToolContext,
    onBack: (() -> Unit)?,
    actions: FlowActions,
) {
    val context = LocalContext.current
    val say = rememberSay(tool)
    var editing by rememberSaveable { mutableStateOf(false) }
    val draft = remember(flow) { RequestDraft.of(flow) }
    if (editing && draft != null) {
        RequestEditor(
            flow,
            draft,
            ToolContext(tool.snackbar, onBack = { editing = false }, tool.onOpenSetup),
            onSent = {
                editing = false
                actions.onOpen(it)
            },
        )
        return
    }
    val labels = remember { AppLabels(context) }
    val rules by TrafficProxy.rules.collectAsStateWithLifecycle()
    val unread = remember(flow, rules) { Unread.of(flow, rules) }
    var trusting by remember { mutableStateOf(false) }
    if (trusting) TrustSheet(flow.uid, say, onDismiss = { trusting = false })
    val readable = !flow.tunnelled || flow.response != null
    DetailFrame(
        title(flow),
        onBack,
        find = "Find in this request".takeIf { readable },
        actions = { FlowMenu(flow, labels, say, actions) },
    ) {
        StatusLine(flow, labels)
        FoundText(
            "0 url",
            flow.url,
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            Modifier.clickable {
                context.copy("URL", flow.url)
                say("Copied the URL")
            },
        )
        // A call that wasn't read has nothing else to show: why, and the fix, come first.
        unread?.let { UnreadSection(flow, it, labels, say, onTrust = { trusting = true }) }
        if (unread !is Unread.Refused) flow.failure?.let { FailureSection(it) }
        if (readable) Sides(flow, say)
        SendAgain(flow, draft, actions, onEdit = { editing = true })
        Overview(flow, labels)
    }
}

/** What a request is called: its method and path, or the host of a connection never read. */
private fun title(flow: Flow): String =
    if (flow.opening) flow.host
    else "${flow.request.method} ${FlowLabels.graphQlOperation(flow) ?: FlowLabels.path(flow)}"

/** The status as a coloured pill, then how long, how much, and which app. */
@Composable
private fun StatusLine(flow: Flow, labels: AppLabels) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusPill(flow)
        Text(
            listOfNotNull(
                    flow.durationMillis?.let(FlowLabels::duration),
                    FlowLabels.size(flow.wireLength).takeIf { flow.wireLength > 0 },
                    flow.app?.let(labels::of),
                )
                .joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The status, or Failed or Passed, on its outcome's colour: what a glance down a list reads. */
@Composable
internal fun StatusPill(flow: Flow) {
    val outcome = FlowLabels.outcome(flow)
    val (container, content) =
        when (outcome) {
            FlowOutcome.Success ->
                MaterialTheme.colorScheme.primaryContainer to
                    MaterialTheme.colorScheme.onPrimaryContainer
            FlowOutcome.ClientError,
            FlowOutcome.ServerError,
            FlowOutcome.Failed ->
                MaterialTheme.colorScheme.errorContainer to
                    MaterialTheme.colorScheme.onErrorContainer
            FlowOutcome.Redirect,
            FlowOutcome.Informational ->
                MaterialTheme.colorScheme.tertiaryContainer to
                    MaterialTheme.colorScheme.onTertiaryContainer
            FlowOutcome.Pending,
            FlowOutcome.PassedThrough ->
                MaterialTheme.colorScheme.surfaceVariant to
                    MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            FlowLabels.status(flow),
            color = content,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * The request, the response or a socket's messages, one at a time: what came back first. While a
 * find is open, each side says how many matches it holds, since only one shows.
 */
@Composable
private fun Sides(flow: Flow, say: (String) -> Unit) {
    val context = LocalContext.current
    // A socket opens on what it said: its request and its 101 are the least of it.
    var side by rememberSaveable {
        mutableIntStateOf(
            when {
                flow.socket -> MESSAGES
                flow.response != null -> RESPONSE
                else -> REQUEST
            }
        )
    }
    // A gRPC call's bodies read with the app's own schema, when the protos folder has it.
    var schemasKept by remember { mutableIntStateOf(0) }
    val ask = rememberServerAsk(flow, say) { schemasKept++ }
    val schemas by
        produceState(Schemas.NONE, flow.id, schemasKept) {
            if (FlowLabels.grpcMethod(flow) != null) {
                value = withContext(Dispatchers.IO) { Schemas.of(ProtoFolder(context)) }
            }
        }
    val query = LocalFinder.current?.query.orEmpty()
    val found by
        produceState(listOf(0, 0), flow.id, query) {
            value = withContext(Dispatchers.Default) { sideMatches(flow, query) }
        }
    val sides = listOfNotNull("Request", "Response", "Messages".takeIf { flow.socket })
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        sides.forEachIndexed { index, label ->
            val count = found.getOrElse(index) { 0 }
            SegmentedButton(
                selected = side == index,
                onClick = { side = index },
                shape = SegmentedButtonDefaults.itemShape(index, sides.size),
            ) {
                Text(if (count > 0) "$label · $count" else label)
            }
        }
    }
    when {
        side == MESSAGES && flow.socket -> SocketMessages(flow.id)
        side == REQUEST ->
            Message(
                flow.request.headers,
                flow.requestBody,
                "request",
                reading = schemas.reading(flow, answer = false, ask),
            )
        else -> {
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
}

/** How many times [query] is in the request's headers and body, and in the response's. */
private fun sideMatches(flow: Flow, query: String): List<Int> {
    if (query.isBlank()) return listOf(0, 0)
    fun count(headers: Headers, body: CapturedBody?): Int {
        val inHeaders =
            headers.all.sumOf {
                ReactotronFind.matches(it.name, query).size +
                    ReactotronFind.matches(it.value, query).size
            }
        val text = bodyText(BodyView.of(headers, body, null))?.take(MAX_SHOWN_CHARS)
        return inHeaders + (text?.let { ReactotronFind.matches(it, query).size } ?: 0)
    }
    val response = flow.response
    return listOf(
        count(flow.request.headers, flow.requestBody),
        if (response == null) 0 else count(response.headers, flow.responseBody),
    )
}

/** A body as the text it shows, or null for one that shows no text: empty, an image, bytes. */
private fun bodyText(shape: BodyShape): String? =
    when (shape) {
        is BodyShape.Json -> shape.pretty
        is BodyShape.Text -> shape.text
        is BodyShape.Grpc -> shape.text
        is BodyShape.Form -> shape.fields.joinToString("\n") { (name, value) -> "$name\n$value" }
        BodyShape.Empty,
        is BodyShape.Image,
        is BodyShape.Binary -> null
    }

/** Send again and edit and send, and why either can't be, under them. */
@Composable
private fun SendAgain(flow: Flow, draft: RequestDraft?, actions: FlowActions, onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    val repeat = remember(flow) { Repeater.prepare(flow) }
    if (flow.opening) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                sending = true
                scope.launch {
                    val sent = TrafficProxy.repeat(context, flow)
                    sending = false
                    // The repeat is a flow of its own, which is where to look next.
                    sent?.let { actions.onOpen(it.id) }
                }
            },
            enabled = repeat is Repeat.Ready && !sending,
        ) {
            Text(if (sending) "Sending…" else "Send again")
        }
        OutlinedButton(onClick = onEdit, enabled = draft != null) { Text("Edit and send") }
    }
    (repeat as? Repeat.Refused)?.let { ToolNote(it.reason) }
    // Why the menu's Mock this response is greyed out.
    if (remember(flow) { Mock.of(flow, "") } == null) Mock.whyNot(flow)?.let { ToolNote(it) }
}

/** When it started, how long it took, its app, how it was carried, and what changed it. */
@Composable
private fun Overview(flow: Flow, labels: AppLabels) {
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
}

/**
 * Everything else a request leads to, in the menu: copying it as cURL, the rules made from it, and
 * the two rules most often made while looking at one, passing its host through or reading only its
 * app. One that can't be made says why, greyed out.
 */
@Composable
private fun FlowMenu(flow: Flow, labels: AppLabels, say: (String) -> Unit, actions: FlowActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { TrafficProxy.loadRules(context) } }
    val rules by TrafficProxy.rules.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val mock = remember(flow) { Mock.of(flow, UUID.randomUUID().toString()) }
    // Neither reaches an HTTP/2 connection, which Mock.whyNot says.
    val rulable = !flow.opening && !flow.tunnelled && !flow.http2
    val host = TrafficRules.pattern(flow.host)
    val app = flow.app
    @Composable
    fun item(text: String, enabled: Boolean = true, action: () -> Unit) =
        MenuChoice(text, enabled) {
            open = false
            action()
        }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(Symbols.byName.getValue("more_vert").outlined), "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            item("Copy as cURL", enabled = !flow.opening) {
                context.copy("cURL", Curl.of(flow))
                say("Copied as cURL")
            }
            // Its answer, kept to give back the next time, and changed if need be.
            item("Mock this response", enabled = mock != null) { mock?.let(actions.onMock) }
            if (rulable) {
                item("Hold requests like this") {
                    actions.onBreak(Breakpoint.of(flow, UUID.randomUUID().toString()))
                }
                item("Send this server's requests elsewhere") {
                    actions.onRemap(Remap.of(flow, UUID.randomUUID().toString()))
                }
            }
            if (host != null && host !in rules.passThrough) {
                item("Pass $host through") {
                    scope.saveRules(context, rules.passingThrough(host))
                    say("$host is passed through from now on")
                }
            }
            if (app != null && rules.apps != listOf(app)) {
                item("Read only ${labels.of(app)}") {
                    scope.saveRules(context, rules.copy(apps = listOf(app)))
                    say("Reading only ${labels.of(app)} from now on")
                }
            }
        }
    }
}

@Composable
private fun MenuChoice(text: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = onClick)
}

private fun connection(flow: Flow): String =
    when {
        flow.repeatOf != null && flow.http2 -> "HTTP/2, sent again by Droidective"
        flow.repeatOf != null && flow.secure -> "HTTPS, sent again by Droidective"
        flow.repeatOf != null -> "HTTP, sent again by Droidective"
        flow.tunnelled && flow.response == null ->
            FlowLabels.passed(flow)?.replaceFirstChar { it.uppercase() } ?: "Passed through"
        flow.failure?.startsWith(TlsFailure.REFUSED) == true ->
            "HTTPS, not read: the app refused the certificate"
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

/**
 * A message: its body decoded, first, since that's what's looked for; then its headers, folded with
 * their count until opened or a find is looking through them; then the trailers.
 */
@Composable
internal fun Message(
    headers: Headers,
    body: CapturedBody?,
    what: String,
    trailers: Headers = Headers(),
    reading: Reading? = null,
) {
    val context = LocalContext.current
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
            is BodyShape.Form ->
                shown.fields.forEachIndexed { index, (name, value) ->
                    NameValue("1 body ${order(index)}", name, value)
                }
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
        val text = shape?.let(::copyable)
        if (text != null) {
            OutlinedButton(onClick = { context.copy("The $what body", text) }) { Text("Copy body") }
        }
    }
    Folding("Headers", headers.all.size) {
        if (headers.all.isEmpty()) ToolNote("None.")
        headers.all.forEachIndexed { index, header ->
            HeaderValue("2 headers ${order(index)}", header, reading?.schema)
        }
    }
    if (trailers.all.isNotEmpty()) {
        ToolSection("Trailers") {
            trailers.all.forEachIndexed { index, header ->
                HeaderValue("3 trailers ${order(index)}", header, reading?.schema)
            }
        }
    }
}

/** What Copy body copies: the whole of what the body shows as text. */
private fun copyable(shape: BodyShape): String? =
    when (shape) {
        is BodyShape.Json -> shape.pretty
        is BodyShape.Text -> shape.text
        is BodyShape.Grpc -> shape.text
        else -> null
    }

/** A find id's number, padded so ids sort in the order the rows show. */
private fun order(index: Int): String = index.toString().padStart(ORDER_DIGITS, '0')

/**
 * A card whose content folds away under its title and count, opened with a tap, and open while a
 * find is looking: a match folded away would be counted and never shown.
 */
@Composable
private fun Folding(title: String, count: Int, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val finding = LocalFinder.current?.query?.isNotBlank() == true
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${title.uppercase()} · $count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(
                        Symbols.byName
                            .getValue(if (open || finding) "expand_less" else "expand_more")
                            .outlined
                    ),
                    contentDescription = if (open) "Fold $title" else "Show $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open || finding) content()
        }
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
        val id = "1 body ${order(index)}"
        when {
            message.problem != null -> ToolNote("Not shown: ${message.problem}.")
            fields == "" -> ToolNote("Empty: every field at its default.")
            fields != null -> TextBody(fields, what, id)
            text != null -> TextBody(text, what, id)
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
private fun TextBody(text: String, what: String, id: String = "1 body") {
    val shown = if (text.length > MAX_SHOWN_CHARS) text.take(MAX_SHOWN_CHARS) else text
    SelectionContainer {
        FoundText(
            id,
            shown,
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            Modifier.padding(4.dp),
        )
    }
    if (shown.length < text.length) {
        ToolNote(
            "Showing the first ${MAX_SHOWN_CHARS / 1_000}k characters of the $what body. Copy " +
                "it for all of it."
        )
    }
}

/** A header, a binary one read as the protobuf it carries rather than shown as base64. */
@Composable
private fun HeaderValue(id: String, header: Header, schema: ProtoSchema?) {
    val read = remember(header, schema) { Grpc.binaryHeader(header.name, header.value, schema) }
    NameValue(id, header.name, read?.trimEnd() ?: header.value)
}

/** A header or a form field: the name small above the value, copied as `name: value` on a tap. */
@Composable
private fun NameValue(id: String, name: String, value: String) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth()
            .clickable { context.copy(name, "$name: $value") }
            .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        FoundText(
            "$id a",
            name,
            MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
        )
        FoundText(
            "$id b",
            value,
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
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

private const val REQUEST = 0
private const val RESPONSE = 1
private const val MESSAGES = 2

/** Header and field rows numbered so their find ids sort in order: up to 1,000 of each. */
private const val ORDER_DIGITS = 3
