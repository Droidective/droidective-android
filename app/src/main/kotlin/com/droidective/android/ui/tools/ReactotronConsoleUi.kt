package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.reactotron.Reactotron
import com.droidective.android.ui.Symbols
import com.droidective.core.reactotron.ReactotronCommands

/**
 * The commands the connected apps registered with `Reactotron.onCustomCommand`, as buttons: each
 * runs in its app, with whatever it asks for typed in first. Grouped by app when there are several.
 */
@Composable
internal fun CommandsSection() {
    val commands by Reactotron.commands.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    val byApp = commands.groupBy { clients[it.connection]?.name ?: "App" }
    ToolSection("Commands") {
        if (commands.isEmpty()) {
            ToolNote(
                "None registered. An app adds its own with Reactotron.onCustomCommand(...), and " +
                    "they show here to run."
            )
        }
        byApp.entries.forEachIndexed { index, (app, offered) ->
            if (byApp.size > 1) {
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    app,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            offered.forEach { command ->
                key(command.connection, command.id) { CommandRow(command) }
            }
        }
    }
}

/** One command: what it's called and does, a field for each argument, and Run. */
@Composable
private fun CommandRow(command: ReactotronCommands.Command) {
    var values by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var ran by rememberSaveable { mutableStateOf(false) }
    val run: @Composable () -> Unit = {
        FilledTonalButton(
            onClick = {
                Reactotron.run(command, values)
                ran = true
            }
        ) {
            Icon(
                painterResource(Symbols.byName.getValue("play_arrow").outlined),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Run")
        }
    }
    Column(
        Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(command.label, style = MaterialTheme.typography.titleSmall)
                command.description
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
            // One with nothing to fill in is a button on the row; the rest run once filled in.
            if (command.args.isEmpty()) run()
        }
        command.args.forEach { arg ->
            OutlinedTextField(
                value = values[arg].orEmpty(),
                onValueChange = { values = values + (arg to it) },
                label = { Text(arg) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (command.args.isNotEmpty()) run()
        if (ran) {
            Text(
                "Sent to the app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
