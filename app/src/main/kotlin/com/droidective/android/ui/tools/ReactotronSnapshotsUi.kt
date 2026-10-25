package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.reactotron.Reactotron
import com.droidective.android.ui.Symbols
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
 * The apps' whole states, kept to put back: taking one from each connected app's store plugin, and
 * the ones kept, each opened to restore, rename or delete. [selected] is the one open beside the
 * list, if any.
 */
@Composable
internal fun SnapshotsSection(selected: String?, onOpen: (String) -> Unit) {
    val snapshots by Reactotron.snapshots.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    if (clients.isNotEmpty()) TakeSnapshot()
    if (snapshots.isNotEmpty()) {
        Text(
            "KEPT ON THIS PHONE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
    snapshots.forEach { snapshot ->
        SnapshotRow(snapshot, selected = snapshot.id == selected) { onOpen(snapshot.id) }
    }
}

/** Asks every connected app for its state, which comes back as a snapshot, or says it didn't. */
@Composable
private fun TakeSnapshot() {
    var askedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var unanswered by remember { mutableStateOf(false) }
    LaunchedEffect(askedAt) {
        val at = askedAt ?: return@LaunchedEffect
        unanswered = false
        delay(ANSWER_MILLIS - (System.currentTimeMillis() - at))
        unanswered = Reactotron.snapshots.value.none { it.takenAt >= at }
    }
    ToolSection("Snapshots") {
        Text(
            "A snapshot is the app's whole state, kept on this phone to put back: a filled cart, " +
                "a login, a step deep in a flow, without going through the app to reach it again.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Button(
            onClick = {
                askedAt = System.currentTimeMillis()
                Reactotron.backUp()
            }
        ) {
            Text("Take a snapshot")
        }
        if (unanswered) {
            ToolNote(
                "No state came back. The app needs reactotron-redux or reactotron-mst, with its " +
                    "store made through Reactotron."
            )
        }
    }
}

@Composable
private fun SnapshotRow(snapshot: ReactotronSnapshot, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color =
            if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(Symbols.byName.getValue("history").outlined),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    snapshot.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${snapshot.app} · ${taken(snapshot)} · ${FlowLabels.size(snapshot.bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painterResource(Symbols.byName.getValue("chevron_right").outlined),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One snapshot: putting it back in its app first, then what it holds. Rename and Delete wait in the
 * menu; Delete asks first, since a snapshot can't be taken again once the app has moved on.
 */
@Composable
internal fun SnapshotDetail(
    snapshot: ReactotronSnapshot,
    tool: ToolContext,
    onBack: (() -> Unit)?,
    onDeleted: () -> Unit,
) {
    val app = LocalContext.current.droidective
    val say = rememberSay(tool)
    val scope = rememberCoroutineScope()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    val connected = clients.values.any { it.name == snapshot.app }
    var state by remember(snapshot.id) { mutableStateOf<JsonElement?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.id) {
        state = withContext(Dispatchers.IO) { Reactotron.state(snapshot) }
    }
    DetailFrame(
        snapshot.name,
        onBack,
        find = "Find in this snapshot",
        actions = { SnapshotMenu(onRename = { renaming = true }, onDelete = { deleting = true }) },
    ) {
        Button(
            enabled = connected,
            onClick = {
                scope.launch {
                    val restored = withContext(Dispatchers.IO) { Reactotron.restore(snapshot) }
                    say(said(restored, snapshot.app))
                }
            },
        ) {
            Icon(
                painterResource(Symbols.byName.getValue("restart_alt").outlined),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Restore")
        }
        ToolNote(
            if (connected) {
                "Restore replaces ${snapshot.app}'s state with this one, through its store plugin."
            } else {
                "Restore puts it back while ${snapshot.app} is connected."
            }
        )
        ToolSection("Overview") {
            InfoRow("App", snapshot.app)
            InfoRow("Taken", taken(snapshot))
            InfoRow("Size", FlowLabels.size(snapshot.bytes))
        }
        val shown = state
        if (shown == null) ToolNote("Reading it…")
        else ReactotronPayload("State", shown, findId = "0 state")
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
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${snapshot.name}”?") },
            text = { Text("Slots and automations that put it back will say it's gone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        onDeleted()
                        app.scope.launch(Dispatchers.IO) { Reactotron.delete(snapshot) }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SnapshotMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(Symbols.byName.getValue("more_vert").outlined), "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    open = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
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
