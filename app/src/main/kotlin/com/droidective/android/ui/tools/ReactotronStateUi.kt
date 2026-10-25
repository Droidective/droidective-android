package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidective.android.reactotron.Reactotron
import com.droidective.core.reactotron.ReactotronCommands

/** Asks the apps for their state, at a path or whole; the answer comes into the timeline. */
@Composable
internal fun StateSection() {
    var path by rememberSaveable { mutableStateOf("") }
    ToolSection("State") {
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("Path, such as user.profile") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Reactotron.askState(path, keys = false) }) { Text("Read value") }
            OutlinedButton(onClick = { Reactotron.askState(path, keys = true) }) {
                Text("List keys")
            }
        }
        ToolNote(
            "Empty reads the whole store. The answer comes into the timeline as VALUE or KEYS; " +
                "the app needs reactotron-redux or reactotron-mst to have a store to give."
        )
        DispatchRow()
    }
}

/** An action dispatched in the apps' stores, as if the app had: a type, or a whole action. */
@Composable
private fun DispatchRow() {
    var text by rememberSaveable { mutableStateOf("") }
    val action = ReactotronCommands.action(text)
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
        Button(enabled = action != null, onClick = { action?.let(Reactotron::dispatch) }) {
            Text("Dispatch")
        }
    }
    ToolNote(
        "A type alone, or a whole action as JSON, such as {\"type\":\"cart/add\",\"item\":\"tea\"}. " +
            "It shows in the timeline as an ACTION once the app has dispatched it."
    )
}
