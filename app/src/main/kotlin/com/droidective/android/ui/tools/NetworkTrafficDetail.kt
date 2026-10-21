package com.droidective.android.ui.tools

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidective.android.ui.copy
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import com.droidective.core.net.proxy.BodyShape
import com.droidective.core.net.proxy.BodyView
import com.droidective.core.net.proxy.Curl
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.FlowLabels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One exchange: where it went, what was sent, what came back, and a way to send it again. */
@Composable
fun FlowDetail(flow: Flow, tool: ToolContext) {
    val context = LocalContext.current
    val say = rememberSay(tool)
    val labels = remember { AppLabels(context) }
    var side by rememberSaveable { mutableIntStateOf(if (flow.response != null) 1 else 0) }
    ToolScaffold("${flow.request.method} ${flow.host}", tool) {
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
            flow.app?.let { InfoRow("App", "${labels.of(it)}\n$it") }
            InfoRow("Started", TIME.format(Instant.ofEpochMilli(flow.startedAt)))
            flow.durationMillis?.let { InfoRow("Took", FlowLabels.duration(it)) }
            if (flow.wireLength > 0) InfoRow("Moved", FlowLabels.size(flow.wireLength))
            InfoRow("Connection", connection(flow))
        }
        flow.failure?.let { FailureSection(it) }
        if (!flow.tunnelled || flow.response != null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Request", "Response").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = side == index,
                        onClick = { side = index },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) {
                        Text(label)
                    }
                }
            }
            if (side == 0) {
                Message(flow.request.headers, flow.requestBody, "request")
            } else {
                val response = flow.response
                if (response == null) {
                    ToolNote(if (flow.failure != null) "Nothing came back." else "Waiting…")
                } else {
                    Message(response.headers, flow.responseBody, "response")
                }
            }
        }
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
        }
    }
}

private fun connection(flow: Flow): String =
    when {
        flow.tunnelled && flow.response == null -> "Passed through, not read"
        flow.tunnelled -> "Upgraded, then carried as bytes"
        flow.secure -> "HTTPS, read through the proxy's certificate"
        else -> "HTTP"
    }

/** What went wrong, and for the commonest failure what to do about it. */
@Composable
private fun FailureSection(failure: String) {
    ToolSection("What happened") {
        Text(failure, color = MaterialTheme.colorScheme.error)
        if (failure.startsWith(REFUSED)) {
            ToolNote(
                "The app wouldn't accept Droidective's certificate. Either the certificate isn't " +
                    "installed, the app doesn't trust user certificates (most apps don't, unless " +
                    "it's your own debug build set up to), or it pins its certificates."
            )
        }
    }
}

/** A message's headers and its body, decoded. */
@Composable
private fun Message(headers: Headers, body: CapturedBody?, what: String) {
    val context = LocalContext.current
    ToolSection("Headers") {
        if (headers.all.isEmpty()) ToolNote("None.")
        headers.forEach { header -> NameValue(header.name, header.value) }
    }
    val shape by
        produceState<BodyShape?>(null, headers, body) {
            value = withContext(Dispatchers.Default) { BodyView.of(headers, body) }
        }
    ToolSection("Body") {
        when (val shown = shape) {
            null -> ToolNote("Reading…")
            BodyShape.Empty -> ToolNote("No body.")
            is BodyShape.Json -> TextBody(shown.pretty, what)
            is BodyShape.Text -> TextBody(shown.text, what)
            is BodyShape.Form -> shown.fields.forEach { (name, value) -> NameValue(name, value) }
            is BodyShape.Image -> ImageBody(shown)
            is BodyShape.Binary ->
                ToolNote("${FlowLabels.size(shown.bytes.size.toLong())}, ${shown.reason}.")
        }
        if (body?.truncated == true) {
            ToolNote(
                "Only the first ${FlowLabels.size(body.bytes.size.toLong())} of " +
                    "${FlowLabels.size(body.wireLength)} was kept. The app got all of it."
            )
        }
        if (shape is BodyShape.Json || shape is BodyShape.Text) {
            OutlinedButton(
                onClick = {
                    val text =
                        when (val shown = shape) {
                            is BodyShape.Json -> shown.pretty
                            is BodyShape.Text -> shown.text
                            else -> ""
                        }
                    context.copy("The $what body", text)
                }
            ) {
                Text("Copy body")
            }
        }
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

/** How the proxy words a handshake the app turned down. */
private const val REFUSED = "the app refused the certificate"
private const val MAX_SHOWN_CHARS = 100_000
private const val MAX_IMAGE_HEIGHT = 320
private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
