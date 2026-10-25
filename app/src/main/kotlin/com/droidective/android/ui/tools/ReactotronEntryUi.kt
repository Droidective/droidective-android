package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.copy
import com.droidective.core.reactotron.ReactotronEvent
import com.droidective.core.reactotron.ReactotronFind
import com.droidective.core.reactotron.ReactotronLabels
import com.droidective.core.reactotron.ReactotronTimeline
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One entry, whole: what it was, and everything it carried, with a find for inside it. */
@Composable
internal fun EntryDetail(
    entry: ReactotronTimeline.Entry,
    tool: ToolContext,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val say = rememberSay(tool)
    val event = entry.event
    DetailFrame(
        ReactotronLabels.tag(event).lowercase().replaceFirstChar { it.uppercase() },
        onBack,
        find = "Find in this entry",
        actions = {
            val payload = entry.command.payload
            if (payload != null) {
                IconButton(
                    onClick = {
                        context.copy("Reactotron payload", prettyPayload(payload))
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
                    ReactotronPayload(
                        if (event.keys) "Keys" else "Value",
                        event.value ?: JsonNull,
                        findId = "0 value",
                    )
                } else {
                    ReactotronPayload("Payload", entry.command.payload, findId = "0 payload")
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
    // What a find has in the side that isn't showing, so the switch can say so.
    val query = LocalFinder.current?.query.orEmpty()
    val found =
        remember(entry.id, query) {
            listOf(
                    event.request.child("headers") to event.request.child("data"),
                    event.response.child("headers") to event.response.child("body"),
                )
                .map { (headers, body) -> foundIn(headers, body, query) }
        }
    FoundText(
        "0 url",
        "${event.method} ${event.url}",
        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
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
                Text(if (found[index] > 0) "$label · ${found[index]}" else label)
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
        ReactotronPayload("Body", event.response.child("body"), findId = "2 body")
    } else {
        Headers(event.request.child("headers"))
        ReactotronPayload("Body", event.request.child("data"), findId = "2 body")
    }
}

/** How many times [query] is in one side of a call, its headers and its body, as they show. */
private fun foundIn(headers: JsonElement?, body: JsonElement?, query: String): Int {
    if (query.isBlank()) return 0
    val fields = (headers as? JsonObject).orEmpty()
    val inHeaders =
        fields.entries.sumOf { (name, value) ->
            ReactotronFind.matches(name, query).size +
                ReactotronFind.matches(headerText(value), query).size
        }
    val inBody =
        body
            ?.takeIf { it != JsonNull }
            ?.let { ReactotronFind.matches(prettyPayload(it).take(MAX_SHOWN), query).size } ?: 0
    return inHeaders + inBody
}

@Composable
private fun LogDetail(event: ReactotronEvent.Log, entry: ReactotronTimeline.Entry) {
    ReactotronPayload("Message", entry.command.payload.child("message"), findId = "0 message")
    if (event.stack.isNotEmpty()) {
        ToolSection("Stack") {
            event.stack.forEachIndexed { index, frame ->
                val at = listOfNotNull(frame.line, frame.column).joinToString(":")
                val id = "1 stack ${index.toString().padStart(ID_DIGITS, '0')}"
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    FoundText(
                        "$id a",
                        frame.functionName.ifEmpty { "(anonymous)" },
                        MaterialTheme.typography.bodyMedium,
                    )
                    FoundText(
                        "$id b",
                        "${frame.fileName} $at",
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
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
        fields.entries.forEachIndexed { index, (name, value) ->
            val id = "1 headers ${index.toString().padStart(ID_DIGITS, '0')}"
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FoundText(
                    "$id a",
                    name,
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    Modifier.weight(NAME_WEIGHT),
                )
                FoundText(
                    "$id b",
                    headerText(value),
                    MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    Modifier.weight(1f - NAME_WEIGHT),
                )
            }
        }
    }
}

private fun headerText(value: JsonElement): String =
    (value as? JsonPrimitive)?.content ?: value.toString()

/**
 * A part of the payload, laid out as JSON — text that is itself JSON, as a body often is, too.
 * [findId] is where it sorts among the detail's other text a find looks through.
 */
@Composable
internal fun ReactotronPayload(title: String, value: JsonElement?, findId: String) {
    if (value == null || value == JsonNull) return
    val shown = remember(value) { prettyPayload(value) }
    ToolSection(title) {
        SelectionContainer {
            FoundText(
                findId,
                shown.take(MAX_SHOWN),
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        if (shown.length > MAX_SHOWN)
            ToolNote("Showing the first ${MAX_SHOWN / 1_000}k characters.")
    }
}

internal fun prettyPayload(value: JsonElement): String {
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

/** Header and stack rows numbered so their find ids sort in order: up to 1,000 of each. */
private const val ID_DIGITS = 3
private const val NAME_WEIGHT = 0.42f
