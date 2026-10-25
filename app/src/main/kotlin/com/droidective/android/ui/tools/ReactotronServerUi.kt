package com.droidective.android.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.reactotron.Reactotron
import com.droidective.android.ui.Symbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The server in one word, for the header: off, waiting, or who is connected. A tap opens the rest.
 */
@Composable
internal fun ReactotronStatusChip(onClick: () -> Unit) {
    val state by Reactotron.state.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    val apps = clients.values.map { it.name }.distinct()
    val (label, dot) =
        when (state) {
            Reactotron.State.Off -> "Off" to MaterialTheme.colorScheme.outline
            is Reactotron.State.Failed -> "Couldn't start" to MaterialTheme.colorScheme.error
            is Reactotron.State.Listening ->
                when (apps.size) {
                    0 -> "Waiting" to MaterialTheme.colorScheme.tertiary
                    1 -> apps.single() to MaterialTheme.colorScheme.primary
                    else -> "${apps.size} apps" to MaterialTheme.colorScheme.primary
                }
        }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Box(Modifier.size(DOT).background(dot, CircleShape)) },
        trailingIcon = {
            Icon(
                painterResource(Symbols.byName.getValue("expand_more").outlined),
                contentDescription = "Server",
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = Modifier.widthIn(max = CHIP_MAX),
    )
}

/** Start and stop, the apps connected now, and how to point one here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactotronServerSheet(onDismiss: () -> Unit) {
    val state by Reactotron.state.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleLarge)
            ServerControls()
            if (state is Reactotron.State.Listening) {
                SheetHeader("Connected")
                if (clients.isEmpty()) {
                    ToolNote("No app yet. Open yours, or reload it, and it connects.")
                }
                clients.values.forEach { ClientRow(it) }
            }
            SheetHeader("How to connect")
            ToolNote(HOW_TO_CONNECT)
        }
    }
}

/**
 * What the screen is until anything is connected or kept: how to connect, in three steps, and the
 * button that does the first.
 */
@Composable
internal fun ReactotronConnectCard(modifier: Modifier = Modifier) {
    val state by Reactotron.state.collectAsStateWithLifecycle()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Connect your app", style = MaterialTheme.typography.titleLarge)
            Text(
                "See a React Native app's logs, API calls and state here, and work with its " +
                    "state, snapshots and commands.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Step(1, "Start Reactotron here.", done = state is Reactotron.State.Listening)
            Step(2, "Open or reload your app's debug build.", done = false)
            Step(
                3,
                "It connects to localhost:9090, which is this phone unless a computer has it " +
                    "with adb reverse: no host to set, no cable.",
                done = false,
            )
            ServerControls()
        }
    }
}

/** Start, stop or try again, with what the server is doing now. */
@Composable
private fun ServerControls() {
    val context = LocalContext.current
    val state by Reactotron.state.collectAsStateWithLifecycle()
    when (val now = state) {
        is Reactotron.State.Listening ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Listening on 127.0.0.1:${now.port}", Modifier.weight(1f))
                OutlinedButton(onClick = { Reactotron.stop(context) }) { Text("Stop") }
            }
        is Reactotron.State.Failed -> {
            Text(now.why, color = MaterialTheme.colorScheme.error)
            if (now.taken) InfoRow("Free it", REVERSE_REMOVE, copyable = true, mono = true)
            Button(onClick = { Reactotron.start(context) }) { Text("Try again") }
        }
        Reactotron.State.Off ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Off", Modifier.weight(1f))
                Button(onClick = { Reactotron.start(context) }) { Text("Start") }
            }
    }
}

@Composable
private fun ClientRow(client: Reactotron.Client) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(client.name, style = MaterialTheme.typography.titleSmall)
        Text(
            listOfNotNull(
                    client.platform,
                    "connected at ${SINCE.format(Instant.ofEpochMilli(client.since))}",
                )
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Step(number: Int, text: String, done: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val color =
            if (done) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondaryContainer
        Box(
            Modifier.size(STEP).background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    painterResource(Symbols.byName.getValue("check_circle").outlined),
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private const val HOW_TO_CONNECT =
    "A React Native app's Reactotron connects to localhost:9090. With no adb reverse from a " +
        "computer, that is this phone, so the debug build you are running reports here: no host " +
        "to set, no cable."
private const val REVERSE_REMOVE = "adb reverse --remove tcp:9090"
private val SINCE =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
private val DOT = 8.dp
private val STEP = 28.dp
private val CHIP_MAX = 200.dp
