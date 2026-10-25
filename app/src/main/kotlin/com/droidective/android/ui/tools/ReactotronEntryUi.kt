package com.droidective.android.ui.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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

/** One entry, whole: what it was, and everything it carried. */
@Composable
internal fun EntryDetail(entry: ReactotronTimeline.Entry, tool: ToolContext) {
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
            is ReactotronEvent.StateAnswer ->
                ReactotronPayload(if (event.keys) "Keys" else "Value", event.value ?: JsonNull)
            is ReactotronEvent.Log -> LogDetail(event, entry)
            else -> ReactotronPayload("Payload", entry.command.payload)
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
    ReactotronPayload("Request body", event.request.child("data"))
    ToolSection("Response") {
        InfoRow(
            "Status",
            if (event.status == 0) "No answer: a network error" else "${event.status}",
        )
        Headers(event.response.child("headers"))
    }
    ReactotronPayload("Response body", event.response.child("body"))
}

@Composable
private fun LogDetail(event: ReactotronEvent.Log, entry: ReactotronTimeline.Entry) {
    ReactotronPayload("Message", entry.command.payload.child("message"))
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
internal fun ReactotronPayload(title: String, value: JsonElement?) {
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
private const val MAX_SHOWN = 100_000
