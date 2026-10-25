package com.droidective.android.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.copy
import com.droidective.core.reactotron.ReactotronEvent
import com.droidective.core.reactotron.ReactotronLabels
import com.droidective.core.reactotron.ReactotronTimeline
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A detail's frame: its title and actions over a scrolling column. With [onBack] it has a back
 * arrow, and system back goes back too; without, it sits beside the list it was opened from.
 */
@Composable
internal fun ReactotronDetail(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = if (onBack == null) 16.dp else 0.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(Symbols.byName.getValue("arrow_back").outlined), "Back")
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            actions()
        }
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** One entry, whole: what it was, and everything it carried. */
@Composable
internal fun EntryDetail(
    entry: ReactotronTimeline.Entry,
    tool: ToolContext,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val say = rememberSay(tool)
    val event = entry.event
    ReactotronDetail(
        ReactotronLabels.tag(event).lowercase().replaceFirstChar { it.uppercase() },
        onBack,
        actions = {
            val payload = entry.command.payload
            if (payload != null) {
                IconButton(
                    onClick = {
                        context.copy("Reactotron payload", pretty(payload))
                        say("Copied the payload")
                    }
                ) {
                    Icon(
                        painterResource(Symbols.byName.getValue("content_copy").outlined),
                        "Copy payload",
                    )
                }
            }
        },
    ) {
        when (event) {
            is ReactotronEvent.ApiResponse -> ApiDetail(entry, event, say)
            is ReactotronEvent.Log -> {
                LogDetail(event, entry)
                Overview(entry)
            }
            else -> {
                Text(ReactotronLabels.summary(event), style = MaterialTheme.typography.bodyMedium)
                if (event is ReactotronEvent.StateAnswer) {
                    ReactotronPayload(if (event.keys) "Keys" else "Value", event.value ?: JsonNull)
                } else {
                    ReactotronPayload("Payload", entry.command.payload)
                }
                Overview(entry)
            }
        }
    }
}

/** When it came, from which app, what type and how big. */
@Composable
private fun Overview(
    entry: ReactotronTimeline.Entry,
    lead: @Composable ColumnScope.() -> Unit = {},
) {
    ToolSection("Overview") {
        lead()
        entry.app?.let { InfoRow("App", it) }
        InfoRow("Received", TIME.format(Instant.ofEpochMilli(entry.at)))
        InfoRow("Type", entry.command.type, mono = true)
        if (entry.size > 0) InfoRow("Size", "${entry.size} bytes")
        if (entry.command.important) InfoRow("Important", "Yes")
    }
}

/** An API call: where it went and how it went, then its request or its response, one at a time. */
@Composable
private fun ApiDetail(
    entry: ReactotronTimeline.Entry,
    event: ReactotronEvent.ApiResponse,
    say: (String) -> Unit,
) {
    val context = LocalContext.current
    // The answer is what's usually looked for; the request is a tap away.
    var side by rememberSaveable(entry.id) { mutableIntStateOf(RESPONSE) }
    Text(
        "${event.method} ${event.url}",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier.clickable {
                context.copy("URL", event.url)
                say("Copied the URL")
            },
    )
    Overview(entry) {
        InfoRow(
            "Status",
            if (event.status == 0) "No answer: a network error" else "${event.status}",
        )
        InfoRow("Took", ReactotronLabels.millis(event.durationMillis))
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SIDES.forEachIndexed { index, label ->
            SegmentedButton(
                selected = side == index,
                onClick = { side = index },
                shape = SegmentedButtonDefaults.itemShape(index, SIDES.size),
            ) {
                Text(label)
            }
        }
    }
    if (side == RESPONSE) {
        val headers = event.response.child("headers")
        if (event.status == 0 && (headers as? JsonObject).isNullOrEmpty()) {
            ToolNote("Nothing came back.")
        } else {
            Headers(headers)
        }
        ReactotronPayload("Body", event.response.child("body"))
    } else {
        Headers(event.request.child("headers"))
        ReactotronPayload("Body", event.request.child("data"))
    }
}

@Composable
private fun LogDetail(event: ReactotronEvent.Log, entry: ReactotronTimeline.Entry) {
    ReactotronPayload("Message", entry.command.payload.child("message"))
    if (event.stack.isNotEmpty()) {
        ToolSection("Stack") {
            event.stack.forEach { frame ->
                val at = listOfNotNull(frame.line, frame.column).joinToString(":")
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        frame.functionName.ifEmpty { "(anonymous)" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${frame.fileName} $at",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Headers(headers: JsonElement?) {
    val fields = (headers as? JsonObject).orEmpty()
    ToolSection("Headers") {
        if (fields.isEmpty()) ToolNote("No headers.")
        fields.forEach { (name, value) ->
            InfoRow(name, (value as? JsonPrimitive)?.content ?: value.toString(), mono = true)
        }
    }
}

/** A part of the payload, laid out as JSON — text that is itself JSON, as a body often is, too. */
@Composable
internal fun ReactotronPayload(title: String, value: JsonElement?) {
    if (value == null || value == JsonNull) return
    val shown = remember(value) { pretty(value) }
    ToolSection(title) {
        SelectionContainer {
            Text(
                shown.take(MAX_SHOWN),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
private val SIDES = listOf("Request", "Response")
private const val RESPONSE = 1
private const val MAX_SHOWN = 100_000
