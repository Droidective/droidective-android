package com.droidective.android.ui.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.automations.AppChooser
import com.droidective.android.ui.copy
import com.droidective.core.logs.LogBuffer
import com.droidective.core.logs.LogLevel
import com.droidective.core.logs.LogLine
import com.droidective.core.logs.Logcat
import com.droidective.core.logs.LogcatFilters
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.shell.AppShell
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the stream stands, for the line under the controls. */
private sealed interface LogStatus {
    data object Starting : LogStatus

    data object Streaming : LogStatus

    data object NoShell : LogStatus

    /** The picked app isn't running; the screen waits for it to start. */
    data object NotRunning : LogStatus

    data class Ended(val message: String) : LogStatus
}

/**
 * The phone's log, live: everything or one app, from a minimum level, searchable. Lines arrive in a
 * background buffer and reach the screen four times a second, so a chatty app can't make the list
 * lay out on every line.
 */
@Composable
fun LogcatTool(tool: ToolContext) {
    val context = LocalContext.current
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var minimum by rememberSaveable { mutableStateOf(LogLevel.Verbose) }
    var query by rememberSaveable { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<LogStatus>(LogStatus.Starting) }
    var restarts by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf(false) }
    val buffer = remember { LogBuffer() }
    var shown by remember { mutableStateOf<List<LogLine>>(emptyList()) }

    LaunchedEffect(packageName, minimum, restarts) {
        status = LogStatus.Starting
        val shell = logShell(context)
        if (shell == null) {
            status = LogStatus.NoShell
            return@LaunchedEffect
        }
        val app = packageName
        val pid = app?.let { pidOf(shell, it) }
        if (app != null && pid == null) {
            status = LogStatus.NotRunning
            // Wait for it to start, then stream it.
            while (pidOf(shell, app) == null) delay(PID_POLL_MILLIS)
            restarts++
            return@LaunchedEffect
        }
        buffer.clear()
        shown = emptyList()
        status = LogStatus.Streaming
        val pending = mutableListOf<LogLine>()
        coroutineScope {
            val flusher = launch {
                while (true) {
                    delay(FLUSH_MILLIS)
                    val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                    if (batch.isNotEmpty()) {
                        buffer.add(batch)
                        if (!paused) shown = buffer.snapshot()
                    }
                }
            }
            // An app that restarts gets a new pid, and --pid would go quiet: follow it.
            val follower = app?.let { pkg ->
                launch {
                    while (true) {
                        delay(PID_POLL_MILLIS)
                        if (pidOf(shell, pkg) != pid) restarts++
                    }
                }
            }
            status =
                try {
                    shell
                        .stream(Logcat.command(LogcatFilters(minimum = minimum.below(), pid = pid)))
                        .collect {
                            val line = Logcat.parse(it)
                            synchronized(pending) { pending += line }
                        }
                    LogStatus.Ended("Logcat stopped.")
                } catch (e: ShellUnavailableException) {
                    LogStatus.Ended(e.message ?: "The shell stopped responding.")
                }
            follower?.cancel()
            flusher.cancel()
        }
    }

    val visible by
        remember(query) { derivedStateOf { shown.filter { LogBuffer.matches(it, query) } } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    // Follow the tail until the person drags, and again once they come to rest at the bottom.
    // The scroll position alone can't decide it: new lines move it on their own.
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) following = false
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }
            .collect { (moving, more) -> if (!moving && !more) following = true }
    }
    LaunchedEffect(visible.size, following) {
        if (following && visible.isNotEmpty()) list.scrollToItem(visible.lastIndex)
    }

    BackHandler(onBack = tool.onBack)
    Scaffold(
        snackbarHost = { SnackbarHost(tool.snackbar) },
        floatingActionButton = {
            if (!following && visible.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = {
                        following = true
                        scope.launch { list.scrollToItem(visible.lastIndex) }
                    }
                ) {
                    Icon(
                        painterResource(Symbols.byName.getValue("vertical_align_bottom").outlined),
                        "Jump to the newest line",
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolTitle("Logcat", tool)
                Box(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        // Resuming shows what arrived while paused.
                        if (paused) shown = buffer.snapshot()
                        paused = !paused
                    }
                ) {
                    Icon(
                        painterResource(
                            Symbols.byName.getValue(if (paused) "play_arrow" else "pause").outlined
                        ),
                        if (paused) "Resume" else "Pause",
                    )
                }
                IconButton(
                    onClick = { context.copy("Logcat", visible.joinToString("\n") { it.raw }) }
                ) {
                    Icon(painterResource(Symbols.byName.getValue("content_copy").outlined), "Copy")
                }
                IconButton(
                    onClick = {
                        buffer.clear()
                        shown = emptyList()
                    }
                ) {
                    Icon(painterResource(Symbols.byName.getValue("delete_sweep").outlined), "Clear")
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { choosing = true }) {
                    Text(packageName?.let { CrashAlerts.label(context, it) } ?: "All apps")
                }
                LEVELS.forEach { level ->
                    FilterChip(
                        selected = minimum == level,
                        onClick = { minimum = level },
                        label = { Text(level.title) },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            StatusLine(status, paused, packageName, visible.size, tool)
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(visible) { _, line -> LogRow(line, context, tool) }
            }
        }
    }

    if (choosing) {
        AppChooser(
            title = "Whose log",
            onDismiss = { choosing = false },
            onPick = {
                packageName = it.packageName
                choosing = false
            },
        )
    }
}

@Composable
private fun StatusLine(
    status: LogStatus,
    paused: Boolean,
    packageName: String?,
    count: Int,
    tool: ToolContext,
) {
    val context = LocalContext.current
    when (status) {
        LogStatus.NoShell -> {
            ToolNote("Reading every app's log needs Shell, through Shizuku or root.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
        }
        LogStatus.NotRunning ->
            ToolNote(
                "${packageName?.let { CrashAlerts.label(context, it) }} isn't running. Its log " +
                    "starts here when it does."
            )
        is LogStatus.Ended -> ToolNote(status.message)
        LogStatus.Starting -> ToolNote("Starting…")
        LogStatus.Streaming ->
            Text(
                if (paused) "Paused · $count lines" else "$count lines",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(line: LogLine, context: Context, tool: ToolContext) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val color =
        when (line.level) {
            LogLevel.Error,
            LogLevel.Fatal -> MaterialTheme.colorScheme.error
            LogLevel.Warn -> MaterialTheme.colorScheme.tertiary
            LogLevel.Verbose,
            LogLevel.Debug -> dim
            else -> MaterialTheme.colorScheme.onSurface
        }
    val say = rememberSay(tool)
    Text(
        buildAnnotatedString {
            val level = line.level
            if (level == null) {
                withStyle(SpanStyle(color = dim)) { append(line.raw) }
                return@buildAnnotatedString
            }
            withStyle(SpanStyle(color = dim)) { append(line.time.substringAfter(' ') + " ") }
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                append("${level.letter} ${line.tag}")
            }
            withStyle(SpanStyle(color = color)) { append(": ${line.message}") }
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        context.copy("Log line", line.raw)
                        say("Copied the line")
                    },
                )
                .padding(vertical = 1.dp),
    )
}

/**
 * Shell when there is one. Before Android 15, READ_LOGS (granted with the Granted level) lets the
 * app read every log itself; Android 13 and 14 ask the person first.
 */
private suspend fun logShell(context: Context): Shell? {
    val app = context.droidective
    app.access.settled()
    app.access.shell()?.let {
        return it
    }
    val canReadLogs =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.checkSelfPermission(Manifest.permission.READ_LOGS) ==
                PackageManager.PERMISSION_GRANTED
    return if (canReadLogs) AppShell() else null
}

private suspend fun pidOf(shell: Shell, packageName: String): Int? =
    try {
        Logcat.parsePid(shell.run(Logcat.pidCommand(packageName)).stdout)
    } catch (_: ShellUnavailableException) {
        null
    }

private val LEVELS =
    listOf(LogLevel.Verbose, LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error)
private const val FLUSH_MILLIS = 250L
private const val PID_POLL_MILLIS = 2_000L

/** Verbose is everything, which needs no filter at all. */
private fun LogLevel.below(): LogLevel? = takeIf { it != LogLevel.Verbose }
