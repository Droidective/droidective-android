package com.droidective.android.ui.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.location.MockLocation
import com.droidective.android.reactotron.Reactotron
import com.droidective.core.automations.Action
import com.droidective.core.location.Places
import com.droidective.core.reactotron.ReactotronCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where Mock Location puts the phone: where it really is, a place kept in Mock Location, or a ready
 * one. [onPick] gets the action and a slot's label for it.
 */
@Composable
fun LocationChooser(onDismiss: () -> Unit, onPick: (Action, String) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { MockLocation.places.load(context) } }
    val kept by MockLocation.places.items.collectAsStateWithLifecycle()
    val places =
        (kept + Places.ready).map { it.name to (Action.SetLocation(it.name, it.where) to it.name) }
    ChoiceDialog(
        "Where the phone is",
        listOf("Where it really is" to (Action.RealLocation to "Real location")) + places,
        onDismiss,
    ) { (action, label) ->
        onPick(action, label)
    }
}

/**
 * A command an app connected to Reactotron offers now, then what to give its arguments, if it has
 * any. The action finds it again by the app's name and its own, so it keeps working each time the
 * app reconnects.
 */
@Composable
fun ReactotronCommandChooser(onDismiss: () -> Unit, onPick: (Action.ReactotronCommand) -> Unit) {
    val commands by Reactotron.commands.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<Pair<String, ReactotronCommands.Command>?>(null) }
    fun runs(app: String, command: ReactotronCommands.Command, args: Map<String, String>) =
        Action.ReactotronCommand(app, command.name, command.label, args)
    chosen?.let { (app, command) ->
        ArgsDialog(command, onDismiss) { onPick(runs(app, command, it)) }
        return
    }
    val offered = commands.mapNotNull { command ->
        clients[command.connection]?.let { "${command.label} · ${it.name}" to (it.name to command) }
    }
    if (offered.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("No commands yet") },
            text = {
                Text(
                    "Start Reactotron in Droidective and open your app. The commands it registers " +
                        "with Reactotron.onCustomCommand show here."
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }
    ChoiceDialog("Which command", offered, onDismiss) { (app, command) ->
        if (command.args.isEmpty()) onPick(runs(app, command, emptyMap()))
        else chosen = app to command
    }
}

/** What each of [command]'s arguments is given, kept with the action and sent on every run. */
@Composable
private fun ArgsDialog(
    command: ReactotronCommands.Command,
    onDismiss: () -> Unit,
    onDone: (Map<String, String>) -> Unit,
) {
    val values = remember { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(command.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                command.args.forEach { arg ->
                    OutlinedTextField(
                        value = values[arg].orEmpty(),
                        onValueChange = { values[arg] = it },
                        label = { Text(arg) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(command.args.associateWith { values[it].orEmpty() }) }) {
                Text("Done")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
