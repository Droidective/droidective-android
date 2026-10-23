package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * State paths watched in the apps: what each holds, kept up to date as the app's state changes, and
 * asked of every app that connects. Shown while an app is connected or any path is watched.
 */
@Composable
internal fun WatchSection() {
    val app = LocalContext.current.droidective
    val watched by Reactotron.watched.collectAsStateWithLifecycle()
    val values by Reactotron.values.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    var path by rememberSaveable { mutableStateOf("") }
    if (watched.isEmpty() && clients.isEmpty()) return
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
        }
        watched.forEach { watching ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(watching, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { app.scope.launch(Dispatchers.IO) { Reactotron.unwatch(watching) } }
                ) {
                    Icon(
                        painterResource(Symbols.byName.getValue("close").outlined),
                        "Stop watching $watching",
                    )
                }
            }
        }
        val said =
            values
                .filterKeys { it in clients }
                .mapValues { (_, changes) ->
                    changes.filter { (at, _) -> ReactotronWatch.answers(at, watched) }
                }
                .filterValues { it.isNotEmpty() }
        if (watched.isNotEmpty() && clients.isNotEmpty() && said.isEmpty()) {
            ToolNote("Nothing back yet: the app sends them once its store is set up.")
        }
        val many = said.size > 1
        said.forEach { (connection, changes) ->
            if (many) {
                Text(clients.getValue(connection).name, style = MaterialTheme.typography.labelLarge)
            }
            changes.forEach { (at, value) -> WatchedValue(at, value) }
        }
    }
}

@Composable
private fun WatchedValue(path: String, value: JsonElement) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            path,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
