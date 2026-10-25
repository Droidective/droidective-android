package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidective.android.reactotron.Reactotron
import com.droidective.android.ui.Symbols
import com.droidective.core.reactotron.ReactotronCommands
import com.droidective.core.reactotron.ReactotronEvent
import com.droidective.core.reactotron.ReactotronLabels
import com.droidective.core.reactotron.ReactotronTimeline
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/** Asks the apps for their state, at a path or whole, and shows what comes back. */
@Composable
internal fun ReadSection(entries: List<ReactotronTimeline.Entry>, onOpen: (Long) -> Unit) {
    var path by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf<Long?>(null) }
    fun ask(keys: Boolean) {
        asked = System.currentTimeMillis()
        Reactotron.askState(path, keys)
    }
    ToolSection("Read state") {
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("Path, such as user.profile") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ask(keys = false) }) { Text("Read value") }
            OutlinedButton(onClick = { ask(keys = true) }) { Text("List keys") }
        }
        ToolNote("Empty reads the whole store. The answer shows here and in the timeline.")
        Answers(entries, asked, NO_STORE, onOpen) { it is ReactotronEvent.StateAnswer }
    }
}

/** An action dispatched in the apps' stores, as if the app had: a type, or a whole action. */
@Composable
internal fun DispatchSection(entries: List<ReactotronTimeline.Entry>, onOpen: (Long) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf<Long?>(null) }
    var sentType by rememberSaveable { mutableStateOf<String?>(null) }
    val action = ReactotronCommands.action(text)
    ToolSection("Dispatch an action") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Action, such as logout") },
                isError = text.isNotBlank() && action == null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = action != null,
                onClick = {
                    if (action != null) {
                        sent = System.currentTimeMillis()
                        sentType = ReactotronCommands.type(text)
                        Reactotron.dispatch(action)
                    }
                },
            ) {
                Text("Dispatch")
            }
        }
        ToolNote(
            "A type alone, or a whole action as JSON, such as {\"type\":\"cart/add\"," +
                "\"item\":\"tea\"}. Once the app has dispatched it, it shows here and in the " +
                "timeline as an ACTION."
        )
        Answers(entries, sent, NOT_REPORTED, onOpen) {
            it is ReactotronEvent.StateAction && it.name == sentType
        }
    }
}

/**
 * What the apps sent back since [since] that [picks] picks out, newest first, each a tap from
 * whole. Until something comes it says it's waiting, and after a few seconds of nothing, [silence].
 */
@Composable
internal fun Answers(
    entries: List<ReactotronTimeline.Entry>,
    since: Long?,
    silence: String,
    onOpen: (Long) -> Unit,
    picks: (ReactotronEvent) -> Boolean,
) {
    if (since == null) return
    val came =
        remember(entries, since) {
            entries.filter { it.at >= since && picks(it.event) }.takeLast(MAX_ANSWERS).asReversed()
        }
    var late by
        remember(since) { mutableStateOf(System.currentTimeMillis() - since >= ANSWER_MILLIS) }
    LaunchedEffect(since) {
        delay(ANSWER_MILLIS - (System.currentTimeMillis() - since))
        late = true
    }
    when {
        came.isNotEmpty() -> came.forEach { AnswerCard(it, onOpen) }
        late -> ToolNote(silence)
        else -> ToolNote("Waiting for the answer…")
    }
}

@Composable
private fun AnswerCard(entry: ReactotronTimeline.Entry, onOpen: (Long) -> Unit) {
    val event = entry.event
    val preview = remember(entry.id) { answered(event)?.let(::prettyPayload)?.take(PREVIEW) }
    Surface(
        onClick = { onOpen(entry.id) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOfNotNull(
                            ReactotronLabels.tag(event),
                            // A REPL answer's summary is its value, which shows below.
                            ReactotronLabels.summary(event).takeUnless {
                                event is ReactotronEvent.ReplResult
                            },
                            entry.app,
                        )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                preview?.let {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = PREVIEW_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                painterResource(Symbols.byName.getValue("chevron_right").outlined),
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What an answer came to, to show a few lines of. */
private fun answered(event: ReactotronEvent): JsonElement? =
    when (event) {
        is ReactotronEvent.StateAnswer -> event.value
        is ReactotronEvent.ReplResult -> event.value
        is ReactotronEvent.StateAction -> event.action
        else -> null
    }

private const val NO_STORE =
    "No answer came back. The app needs reactotron-redux or reactotron-mst, with its store made " +
        "through Reactotron, to have a store to give."
private const val NOT_REPORTED =
    "The app hasn't reported it. It needs reactotron-redux or reactotron-mst, with its store " +
        "made through Reactotron."
private const val ANSWER_MILLIS = 4_000L
private const val MAX_ANSWERS = 3
private const val PREVIEW = 2_000
private const val PREVIEW_LINES = 8
