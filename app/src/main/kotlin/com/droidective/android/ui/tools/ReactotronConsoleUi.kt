package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.reactotron.Reactotron
import com.droidective.core.reactotron.ReactotronCommands

/**
 * The commands the connected apps registered with `Reactotron.onCustomCommand`, as buttons: each
 * runs in its app, with whatever it asks for typed in first.
 */
@Composable
internal fun CommandsSection() {
    val commands by Reactotron.commands.collectAsStateWithLifecycle()
    ToolSection("Commands") {
        if (commands.isEmpty()) {
            ToolNote(
                "None registered. An app adds its own with Reactotron.onCustomCommand(...), and " +
                    "they show here to run."
            )
        }
        commands.forEach { command -> key(command.connection, command.id) { CommandRow(command) } }
    }
}

@Composable
private fun CommandRow(command: ReactotronCommands.Command) {
    val values = remember { mutableStateMapOf<String, String>() }
    var ran by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(command.label, style = MaterialTheme.typography.titleSmall)
        command.description?.takeIf { it.isNotBlank() }?.let { ToolNote(it) }
        command.args.forEach { arg ->
            OutlinedTextField(
                value = values[arg].orEmpty(),
                onValueChange = { values[arg] = it },
                label = { Text(arg) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    Reactotron.run(command, values.toMap())
                    ran = true
                }
            ) {
                Text("Run")
            }
            if (ran) Text("  Sent to the app", style = MaterialTheme.typography.bodySmall)
        }
    }
}
