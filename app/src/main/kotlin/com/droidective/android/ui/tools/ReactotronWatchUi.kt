package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.reactotron.Reactotron
import com.droidective.android.ui.Symbols
import com.droidective.core.reactotron.ReactotronWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * State paths watched in the apps, each with what it holds now, kept up to date as the app's state
 * changes and asked of every app that connects. Shown while an app is connected or any path is
 * watched.
 */
@Composable
internal fun WatchSection() {
    val app = LocalContext.current.droidective
    val watched by Reactotron.watched.collectAsStateWithLifecycle()
    val values by Reactotron.values.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    var path by rememberSaveable { mutableStateOf("") }
    if (watched.isEmpty() && clients.isEmpty()) return
    val readings =
        remember(watched, values, clients) {
            ReactotronWatch.readings(watched, values, clients.keys)
        }
    val many = clients.values.map { it.name }.distinct().size > 1
    ToolSection("Watch") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Path, such as cart.items") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = ReactotronWatch.path(path) != null,
                onClick = {
                    val adding = path
                    path = ""
                    app.scope.launch(Dispatchers.IO) { Reactotron.watch(adding) }
                },
            ) {
                Text("Watch")
            }
        }
        if (watched.isEmpty()) {
            ToolNote(
                "A watched path's value shows here and changes as the app's does. End one with " +
                    ".* to watch each key under it. The app needs reactotron-redux or " +
                    "reactotron-mst."
            )
        } else if (clients.isEmpty()) {
            ToolNote("Their values show while an app is connected.")
        }
        readings.forEach { (watching, read) ->
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            WatchedPath(
                watching,
                read,
                waiting = clients.isNotEmpty() && read.isEmpty(),
                appOf = { connection -> clients[connection]?.name?.takeIf { many } },
                onRemove = { app.scope.launch(Dispatchers.IO) { Reactotron.unwatch(watching) } },
            )
        }
    }
}

/** One watched path, its remove button, and what each connected app says it holds. */
@Composable
private fun WatchedPath(
    path: String,
    readings: List<ReactotronWatch.Reading>,
    waiting: Boolean,
    appOf: (Int) -> String?,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    painterResource(Symbols.byName.getValue("close").outlined),
                    "Stop watching $path",
                )
            }
        }
        if (waiting) ToolNote("Nothing back yet: the app sends it once its store is set up.")
        readings.forEach { reading ->
            // A starred path's readings are its keys, each named; the path itself needs no name.
            val label =
                listOfNotNull(reading.path.takeIf { it != path }, appOf(reading.connection))
                    .joinToString(" · ")
            WatchedValue(label, reading.value)
        }
    }
}

@Composable
private fun WatchedValue(label: String, value: JsonElement) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            shown(value),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A string as itself, anything else as compact JSON; a path holding nothing says so. */
private fun shown(value: JsonElement): String =
    when {
        value is JsonPrimitive && value.isString -> value.content
        value is JsonNull -> "nothing (undefined or null)"
        else -> value.toString()
    }

private const val MAX_LINES = 6
