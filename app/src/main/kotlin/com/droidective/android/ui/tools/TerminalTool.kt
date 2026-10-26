package com.droidective.android.ui.tools

import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.copy
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.shell.Stoppable
import com.droidective.core.shell.Terminal
import com.droidective.core.shell.TerminalHistory
import com.droidective.platform.shell.AppShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One thing the terminal shows: a line someone ran, a line it printed, or how it ended. */
private sealed interface TermLine {
    data class Typed(val cwd: String, val text: String) : TermLine

    data class Printed(val text: String) : TermLine

    data class Ended(val exitCode: Int) : TermLine
}

/**
 * The Mac's Terminal, on the phone: a shell prompt as root, the shell user or the app, with `cd`
 * that sticks, history on the arrow buttons, and Stop for a command that doesn't end. Each line
 * runs on its own, so a program that wants a real terminal (vi, top without `-n`) won't work.
 */
@Composable
fun TerminalTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val who =
        when {
            access.shell?.isRoot == true -> "root"
            access.shell != null -> "shell"
            else -> "app"
        }
    val home = if (access.shell == null) context.filesDir.path else Terminal.HOME
    var cwd by rememberSaveable(who) { mutableStateOf(home) }
    var input by rememberSaveable { mutableStateOf("") }
    val lines = remember { mutableStateListOf<TermLine>() }
    val history = remember { TerminalHistory() }
    var running by remember { mutableStateOf<Job?>(null) }
    var pid by remember { mutableStateOf<Int?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun run(line: String) {
        if (line.isBlank() || running != null) return
        history.add(line)
        input = ""
        lines += TermLine.Typed(cwd, line)
        running = scope.launch {
            val shell: Shell = context.droidective.access.shell() ?: AppShell()
            try {
                shell.stream(Terminal.wrap(line, cwd, Process.myPid())).collect { printed ->
                    val ending = Terminal.ending(printed)
                    val started = Stoppable.pid(printed)
                    if (started != null) {
                        pid = started
                    } else if (ending == null) {
                        lines += TermLine.Printed(printed)
                        while (lines.size > MAX_LINES) lines.removeAt(0)
                    } else {
                        cwd = ending.cwd
                        if (ending.exitCode != 0) lines += TermLine.Ended(ending.exitCode)
                    }
                }
            } catch (e: ShellUnavailableException) {
                lines += TermLine.Printed(e.message ?: "The shell stopped responding.")
            } finally {
                running = null
                pid = null
            }
        }
    }

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    // Leaving ends the stream; as root that alone leaves the command running, so end it too.
    DisposableEffect(Unit) {
        onDispose { pid?.let { context.droidective.scope.launch { stopCommand(context, it) } } }
    }

    BackHandler(onBack = tool.onBack)
    Scaffold(snackbarHost = { SnackbarHost(tool.snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolTitle("Terminal", tool)
                Box(Modifier.weight(1f))
                IconButton(onClick = { context.copy("Terminal", transcript(lines)) }) {
                    Icon(painterResource(Symbols.byName.getValue("content_copy").outlined), "Copy")
                }
                IconButton(onClick = { lines.clear() }) {
                    Icon(painterResource(Symbols.byName.getValue("delete_sweep").outlined), "Clear")
                }
            }
            Text(
                when (who) {
                    "root" -> "As root"
                    "shell" -> "As the shell user, through Shizuku"
                    else -> "As this app: start Shizuku or allow root for more"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                state = list,
                modifier =
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(8.dp),
            ) {
                items(lines) { line -> TerminalRow(line, who) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    input,
                    { input = it },
                    prefix = { Text(prompt(who), fontFamily = FontFamily.Monospace) },
                    placeholder = { Text(cwd, fontFamily = FontFamily.Monospace) },
                    textStyle =
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { run(input) }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { history.older()?.let { input = it } }) {
                    Icon(
                        painterResource(Symbols.byName.getValue("arrow_upward").outlined),
                        "Earlier",
                    )
                }
                IconButton(onClick = { input = history.newer() }) {
                    Icon(
                        painterResource(Symbols.byName.getValue("arrow_downward").outlined),
                        "Later",
                    )
                }
                if (running == null) {
                    IconButton(enabled = input.isNotBlank(), onClick = { run(input) }) {
                        Icon(painterResource(Symbols.byName.getValue("play_arrow").outlined), "Run")
                    }
                } else {
                    IconButton(
                        onClick = {
                            val job = running
                            val wrapper = pid
                            scope.launch {
                                // Kill it where it runs, then stop reading: as root, closing the
                                // stream alone leaves a quiet command running.
                                wrapper?.let { stopCommand(context, it) }
                                job?.cancel()
                            }
                        }
                    ) {
                        Icon(
                            painterResource(Symbols.byName.getValue("stop_circle").outlined),
                            "Stop",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalRow(line: TermLine, who: String) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        when (line) {
            is TermLine.Typed ->
                Text(
                    "${line.cwd} ${prompt(who)}${line.text}",
                    style = mono,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            is TermLine.Printed -> Text(line.text, style = mono)
            is TermLine.Ended ->
                Text("exit ${line.exitCode}", style = mono, color = MaterialTheme.colorScheme.error)
        }
    }
}

private suspend fun stopCommand(context: android.content.Context, pid: Int) {
    // As the app, the app's own shell can end its own processes.
    val shell: Shell = context.droidective.access.shell() ?: AppShell()
    try {
        shell.run(Stoppable.stop(pid))
    } catch (_: ShellUnavailableException) {
        // The shell went away, and the command with it.
    }
}

private fun prompt(who: String) = if (who == "root") "# " else "$ "

private fun transcript(lines: List<TermLine>): String =
    lines.joinToString("\n") {
        when (it) {
            is TermLine.Typed -> "${it.cwd} $ ${it.text}"
            is TermLine.Printed -> it.text
            is TermLine.Ended -> "exit ${it.exitCode}"
        }
    }

private const val MAX_LINES = 5_000
