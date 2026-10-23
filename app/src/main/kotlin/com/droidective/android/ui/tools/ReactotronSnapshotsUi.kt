package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.reactotron.Reactotron
import com.droidective.android.ui.automations.TextDialog
import com.droidective.core.net.proxy.FlowLabels
import com.droidective.core.reactotron.ReactotronSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * The apps' whole states, kept to put back: taken here, from each connected app's store plugin, and
 * opened to restore, rename or delete. Shown while an app is connected or any are kept.
 */
@Composable
internal fun SnapshotsSection(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val snapshots by Reactotron.snapshots.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    var askedAt by remember { mutableStateOf<Long?>(null) }
    var unanswered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Reactotron.loadSnapshots(context) } }
    LaunchedEffect(askedAt) {
        val at = askedAt ?: return@LaunchedEffect
        unanswered = false
        delay(ANSWER_MILLIS)
        unanswered = Reactotron.snapshots.value.none { it.takenAt >= at }
    }
    if (snapshots.isEmpty() && clients.isEmpty()) return
    ToolSection("Snapshots") {
        if (clients.isNotEmpty()) {
            Button(
                onClick = {
                    askedAt = System.currentTimeMillis()
                    Reactotron.backUp()
                }
            ) {
                Text("Take a snapshot")
            }
        }
        if (unanswered) {
            ToolNote(
                "No state came back. The app needs reactotron-redux or reactotron-mst, with its " +
                    "store made through Reactotron."
            )
        }
        if (snapshots.isEmpty()) {
            ToolNote(
                "A snapshot is the app's whole state, kept to put back: a filled cart, a login, " +
                    "a step deep in a flow, without going through the app to reach it again."
            )
        }
        snapshots.forEach { snapshot -> SnapshotRow(snapshot) { onOpen(snapshot.id) } }
    }
}

@Composable
private fun SnapshotRow(snapshot: ReactotronSnapshot, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(snapshot.name, style = MaterialTheme.typography.titleSmall)
        Text(
            "${snapshot.app} · ${taken(snapshot)} · ${FlowLabels.size(snapshot.bytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One snapshot: what it holds, and putting it back in its app. */
@Composable
internal fun SnapshotDetail(snapshot: ReactotronSnapshot, tool: ToolContext) {
    val app = LocalContext.current.droidective
    val say = rememberSay(tool)
    val scope = rememberCoroutineScope()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    val connected = clients.values.any { it.name == snapshot.app }
    var state by remember(snapshot.id) { mutableStateOf<JsonElement?>(null) }
    var renaming by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.id) {
        state = withContext(Dispatchers.IO) { Reactotron.state(snapshot) }
    }
    ToolScaffold(snapshot.name, tool) {
        ToolSection("Overview") {
            InfoRow("App", snapshot.app)
            InfoRow("Taken", taken(snapshot))
            InfoRow("Size", FlowLabels.size(snapshot.bytes))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = connected,
                onClick = {
                    scope.launch {
                        val restored = withContext(Dispatchers.IO) { Reactotron.restore(snapshot) }
                        say(said(restored, snapshot.app))
                    }
                },
            ) {
                Text("Restore")
            }
            OutlinedButton(onClick = { renaming = true }) { Text("Rename") }
            OutlinedButton(
                onClick = {
                    tool.onBack()
                    app.scope.launch(Dispatchers.IO) { Reactotron.delete(snapshot) }
                }
            ) {
                Text("Delete")
            }
        }
        ToolNote(
            if (connected) {
                "Restore replaces ${snapshot.app}'s state with this one, through its store plugin."
            } else {
                "Restore puts it back while ${snapshot.app} is connected."
            }
        )
        val shown = state
        if (shown == null) ToolNote("Reading it…") else ReactotronPayload("State", shown)
    }
    if (renaming) {
        TextDialog(
            "Rename",
            "Name",
            initial = snapshot.name,
            onDismiss = { renaming = false },
        ) { name ->
            renaming = false
            app.scope.launch(Dispatchers.IO) { Reactotron.rename(snapshot, name) }
        }
    }
}

private fun said(restored: Reactotron.Restore, app: String): String =
    when (restored) {
        Reactotron.Restore.Sent -> "Put back in $app"
        Reactotron.Restore.NotConnected -> "$app isn't connected"
        Reactotron.Restore.Gone -> "It's gone from the phone's files"
    }

private fun taken(snapshot: ReactotronSnapshot): String =
    TAKEN.format(Instant.ofEpochMilli(snapshot.takenAt))

private val TAKEN =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
private const val ANSWER_MILLIS = 4_000L
