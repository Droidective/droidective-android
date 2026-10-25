package com.droidective.android.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.reactotron.Reactotron
import com.droidective.android.ui.AdaptiveColumns
import com.droidective.android.ui.Symbols
import com.droidective.core.reactotron.ReactotronEvent
import com.droidective.core.reactotron.ReactotronKind
import com.droidective.core.reactotron.ReactotronLabels
import com.droidective.core.reactotron.ReactotronSnapshot
import com.droidective.core.reactotron.ReactotronTimeline
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Where the tool's parts live, as its tabs name them. */
private enum class ReactotronTab(val title: String) {
    Timeline("Timeline"),
    State("State"),
    Snapshots("Snapshots"),
    Console("Console"),
}

/** What's open, one entry or one snapshot at a time, and how to open another. */
private class ReactotronOpen(
    val entry: Long?,
    val snapshot: String?,
    val onEntry: (Long) -> Unit,
    val onSnapshot: (String) -> Unit,
)

/**
 * Reactotron on the phone: what the connected apps say — logs, API calls, state — newest first,
 * with their state, snapshots and commands a tab away. The server is one chip in the header; until
 * anything is connected or kept, one card says how to connect. Where two panes fit, what's opened
 * shows beside the list instead of in its place. The list catches up a few times a second rather
 * than on every command, so a chatty app can't make it lay out a hundred times a second.
 */
@Composable
fun ReactotronTool(tool: ToolContext) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(Reactotron.timeline.snapshot()) }
    LaunchedEffect(Unit) {
        Reactotron.changes.collect {
            entries = Reactotron.timeline.snapshot()
            delay(PACE_MILLIS)
        }
    }
    // Snapshots and watched paths come from the app's files; until then, nothing is known kept.
    val loaded by
        produceState(false) {
            withContext(Dispatchers.IO) { Reactotron.load(context) }
            value = true
        }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    var openSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    val snapshots by Reactotron.snapshots.collectAsStateWithLifecycle()
    val kept = rememberSaveableStateHolder()
    // Gone from the timeline or deleted, it closes.
    val entry = open?.let { id -> entries.firstOrNull { it.id == id } }
    val snapshot = openSnapshot?.let { id -> snapshots.firstOrNull { it.id == id } }
    val opened =
        ReactotronOpen(
            open,
            openSnapshot,
            onEntry = {
                open = it
                openSnapshot = null
            },
            onSnapshot = {
                openSnapshot = it
                open = null
            },
        )
    val closeSnapshot = { openSnapshot = null }
    val beside: @Composable (ReactotronTab) -> Unit = { tab ->
        OpenPane(tab, entry, snapshot, tool, onDeleted = closeSnapshot)
    }
    AdaptiveColumns(Modifier.fillMaxSize(), minWidth = PANE_MIN) { columns ->
        val twoPanes = columns > 1
        when {
            !twoPanes && entry != null ->
                DetailScaffold(tool) { EntryDetail(entry, tool, onBack = { open = null }) }
            !twoPanes && snapshot != null ->
                DetailScaffold(tool) {
                    SnapshotDetail(
                        snapshot,
                        tool,
                        onBack = closeSnapshot,
                        onDeleted = closeSnapshot,
                    )
                }
            else ->
                kept.SaveableStateProvider(MAIN) {
                    Main(tool, entries, loaded, opened, detail = beside.takeIf { twoPanes })
                }
        }
    }
}

/** The pane beside the list: what's open there, or, with nothing open, what opens there. */
@Composable
private fun OpenPane(
    tab: ReactotronTab,
    entry: ReactotronTimeline.Entry?,
    snapshot: ReactotronSnapshot?,
    tool: ToolContext,
    onDeleted: () -> Unit,
) {
    when {
        entry != null -> key(entry.id) { EntryDetail(entry, tool, onBack = null) }
        snapshot != null ->
            key(snapshot.id) { SnapshotDetail(snapshot, tool, onBack = null, onDeleted) }
        else -> Pick(tab)
    }
}

/** A detail on its own, in place of the list: a phone's way. */
@Composable
private fun DetailScaffold(tool: ToolContext, content: @Composable () -> Unit) {
    Scaffold(snackbarHost = { SnackbarHost(tool.snackbar) }) { padding ->
        Box(Modifier.padding(padding)) { content() }
    }
}

/**
 * The header, then the card that says how to connect, or the tabs once there's anything. With
 * [detail], the tabs take the left pane and [detail] the right, given the tab showing.
 */
@Composable
private fun Main(
    tool: ToolContext,
    entries: List<ReactotronTimeline.Entry>,
    loaded: Boolean,
    opened: ReactotronOpen,
    detail: (@Composable (ReactotronTab) -> Unit)?,
) {
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    val snapshots by Reactotron.snapshots.collectAsStateWithLifecycle()
    val watched by Reactotron.watched.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(ReactotronTab.Timeline.name) }
    var server by rememberSaveable { mutableStateOf(false) }
    val tab = ReactotronTab.valueOf(tabName)
    val nothing = clients.isEmpty() && entries.isEmpty() && snapshots.isEmpty() && watched.isEmpty()
    val tabs: @Composable () -> Unit = { Tabs(tab, entries, opened, onTab = { tabName = it.name }) }
    BackHandler(onBack = tool.onBack)
    Scaffold(snackbarHost = { SnackbarHost(tool.snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolTitle("Reactotron", tool)
                Spacer(Modifier.weight(1f))
                ReactotronStatusChip(onClick = { server = true })
            }
            when {
                !loaded -> Unit
                nothing ->
                    Column(
                        Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ReactotronConnectCard(Modifier.widthIn(max = CARD_MAX))
                    }
                detail == null -> tabs()
                else ->
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxHeight()) { tabs() }
                        VerticalDivider()
                        Box(Modifier.weight(DETAIL_WEIGHT).fillMaxHeight()) { detail(tab) }
                    }
            }
        }
    }
    if (server) ReactotronServerSheet(onDismiss = { server = false })
}

/** The tab row, and the tab showing, each keeping its fields and scroll while another shows. */
@Composable
private fun Tabs(
    tab: ReactotronTab,
    entries: List<ReactotronTimeline.Entry>,
    opened: ReactotronOpen,
    onTab: (ReactotronTab) -> Unit,
) {
    val kept = rememberSaveableStateHolder()
    PrimaryTabRow(selectedTabIndex = tab.ordinal) {
        ReactotronTab.entries.forEach { each ->
            Tab(
                selected = tab == each,
                onClick = { onTab(each) },
                text = { Text(each.title, maxLines = 1) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    kept.SaveableStateProvider(tab.name) {
        when (tab) {
            ReactotronTab.Timeline -> TimelineTab(entries, opened.entry, opened.onEntry)
            ReactotronTab.State -> StateTab(entries, opened.onEntry)
            ReactotronTab.Snapshots -> SnapshotsTab(opened.snapshot, opened.onSnapshot)
            ReactotronTab.Console -> ConsoleTab(entries, opened.onEntry)
        }
    }
}

/** The right pane with nothing open in it, saying what opens there. */
@Composable
private fun Pick(tab: ReactotronTab) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(
                Symbols.byName
                    .getValue(if (tab == ReactotronTab.Snapshots) "history" else "data_object")
                    .outlined
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            when (tab) {
                ReactotronTab.Timeline -> "Pick an entry to see it whole here."
                ReactotronTab.Snapshots -> "Pick a snapshot to see what it holds."
                ReactotronTab.State,
                ReactotronTab.Console -> "Tap an answer to see it whole here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The timeline: search and kinds above, the count and Clear, then the entries, newest first. */
@Composable
private fun TimelineTab(
    entries: List<ReactotronTimeline.Entry>,
    selected: Long?,
    onOpen: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = kindName?.let(ReactotronKind::valueOf)
    val visible =
        remember(entries, kind, query) {
            entries.filter { ReactotronLabels.matches(it, kind, query) }.asReversed()
        }
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    // Labelled by app once more than one has spoken, connected now or not.
    val many = remember(entries) { entries.mapNotNullTo(HashSet()) { it.app }.size > 1 }
    if (entries.isEmpty() && clients.isEmpty()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            ReactotronConnectCard()
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Filters(kind, query, onKind = { kindName = it?.name }, onQuery = { query = it })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            // First in the list, so a list read from the top keeps showing the newest: a list
            // holds its first visible item in place as items arrive above it.
            item(key = COUNT) {
                Count(
                    if (entries.isEmpty()) "Connected. What the app says shows here."
                    else "${visible.size} of ${entries.size}",
                    clearable = entries.isNotEmpty(),
                )
            }
            items(visible, key = { it.id }) { entry ->
                EntryRow(entry, many, selected = entry.id == selected) { onOpen(entry.id) }
            }
        }
    }
}

/** How many entries show of how many there are, and Clear. */
@Composable
private fun Count(text: String, clearable: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
        if (clearable) {
            TextButton(onClick = Reactotron::clear) {
                Icon(
                    painterResource(Symbols.byName.getValue("delete_sweep").outlined),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Clear")
            }
        }
    }
}

@Composable
private fun Filters(
    kind: ReactotronKind?,
    query: String,
    onKind: (ReactotronKind?) -> Unit,
    onQuery: (String) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search") },
            leadingIcon = {
                Icon(painterResource(Symbols.byName.getValue("search").outlined), null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(
                            painterResource(Symbols.byName.getValue("close").outlined),
                            "Clear the search",
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = kind == null, onClick = { onKind(null) }, label = { Text("All") })
            ReactotronKind.entries.forEach { each ->
                FilterChip(
                    selected = kind == each,
                    onClick = { onKind(if (kind == each) null else each) },
                    label = { Text(each.title) },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: ReactotronTimeline.Entry,
    showApp: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val event = entry.event
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(TAG_WIDTH.dp)) {
            Text(
                ReactotronLabels.tag(event),
                color = tagColor(event),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                TIME.format(Instant.ofEpochMilli(entry.at)),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                ReactotronLabels.summary(event),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (showApp && entry.app != null) {
                Text(
                    entry.app.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun tagColor(event: ReactotronEvent): Color =
    when {
        event is ReactotronEvent.Log && event.level == ReactotronEvent.Log.Level.Error ->
            MaterialTheme.colorScheme.error
        event is ReactotronEvent.Log && event.level == ReactotronEvent.Log.Level.Warn ->
            MaterialTheme.colorScheme.tertiary
        event is ReactotronEvent.ApiResponse && (event.status == 0 || event.status >= 400) ->
            MaterialTheme.colorScheme.error
        event is ReactotronEvent.Unknown && event.type == ReactotronTimeline.GONE ->
            MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }

/** Reading and changing the apps' state, and the paths watched in it. */
@Composable
private fun StateTab(entries: List<ReactotronTimeline.Entry>, onOpen: (Long) -> Unit) {
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    TabColumn {
        if (clients.isEmpty()) {
            TabNote("Connect an app to read its state or dispatch an action.")
        } else {
            ReadSection(entries, onOpen)
            DispatchSection(entries, onOpen)
        }
        WatchSection()
    }
}

@Composable
private fun SnapshotsTab(selected: String?, onOpen: (String) -> Unit) {
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    val snapshots by Reactotron.snapshots.collectAsStateWithLifecycle()
    TabColumn {
        if (clients.isEmpty() && snapshots.isEmpty()) {
            TabNote("Connect an app to take a snapshot of its state, kept here to put back.")
        }
        SnapshotsSection(selected, onOpen)
    }
}

/** What runs in the apps: the commands they registered, and code in their REPL. */
@Composable
private fun ConsoleTab(entries: List<ReactotronTimeline.Entry>, onOpen: (Long) -> Unit) {
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    TabColumn {
        if (clients.isEmpty()) {
            TabNote("Connect an app to run its commands or code in it.")
        } else {
            CommandsSection()
            ReplSection(entries, onOpen)
        }
    }
}

@Composable
internal fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A tab with nothing to show until an app connects, saying so. */
@Composable
internal fun TabNote(text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }
}

private const val MAIN = "main"

/** Two panes once each gets this much: the tabs and list, and what's open beside them. */
private val PANE_MIN = 400.dp

private const val DETAIL_WEIGHT = 1.2f
private val CARD_MAX = 560.dp
private const val COUNT = "count"
private const val PACE_MILLIS = 250L
private const val TAG_WIDTH = 92
