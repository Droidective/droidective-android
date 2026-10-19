package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.surface.ShellRuns
import com.droidective.android.ui.automations.AppChooser
import com.droidective.android.ui.openSafely
import com.droidective.core.apps.MemInfo
import com.droidective.core.apps.ProcessMemory

/** What the last read of an app's memory found. */
private sealed interface MemoryReading {
    data class Running(val processes: List<ProcessMemory>) : MemoryReading

    data object NotRunning : MemoryReading

    data object NoShell : MemoryReading

    data class Failed(val message: String) : MemoryReading
}

/**
 * One app's memory, read again every two seconds while on screen: the Mac's Memory Usage. Another
 * app's memory needs the shell: as the app, Android refuses the meminfo service outright.
 */
@Composable
fun MemoryTool(tool: ToolContext) {
    val context = LocalContext.current
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<MemoryReading?>(null) }
    var choosing by remember { mutableStateOf(false) }

    packageName?.let { pkg -> WhileVisible(REFRESH_MILLIS) { reading = read(context, pkg) } }

    ToolScaffold("Memory Usage", tool) {
        ToolSection("App") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(packageName?.let { CrashAlerts.label(context, it) } ?: "No app picked")
                    packageName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = { choosing = true }) {
                    Text(if (packageName == null) "Pick an app" else "Change")
                }
            }
        }
        when (val now = reading) {
            null ->
                if (packageName != null) ToolNote("Reading…")
                else ToolNote("Pick an app to see its memory, read again every two seconds.")
            MemoryReading.NoShell -> {
                ToolNote("Reading another app's memory needs Shell, through Shizuku or root.")
                OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            }
            is MemoryReading.Failed -> ToolNote(now.message)
            MemoryReading.NotRunning -> {
                val pkg = packageName.orEmpty()
                ToolNote("${CrashAlerts.label(context, pkg)} isn't running.")
                context.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                    OutlinedButton(onClick = { context.openSafely(launch) }) { Text("Open it") }
                }
            }
            is MemoryReading.Running -> now.processes.forEach { ProcessCard(it) }
        }
    }

    if (choosing) {
        AppChooser(
            title = "Which app",
            onDismiss = { choosing = false },
            onPick = {
                packageName = it.packageName
                reading = null
                choosing = false
            },
        )
    }
}

@Composable
private fun ProcessCard(memory: ProcessMemory) {
    // Section titles are upper-cased, which would mangle a process name; it gets a row instead.
    ToolSection("Memory") {
        InfoRow("Process", "${memory.process} · pid ${memory.pid}", mono = true)
        Row(
            Modifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Total("Total PSS", memory.totalPssKb)
            Total("Total RSS", memory.totalRssKb)
        }
        memory.summary.forEach { row ->
            InfoRow(
                row.label,
                listOfNotNull(
                        row.pssKb?.let { MemInfo.size(it) },
                        row.rssKb?.let { "RSS ${MemInfo.size(it)}" },
                    )
                    .joinToString(" · ")
                    .ifEmpty { null },
            )
        }
        val counted = OBJECTS.mapNotNull { (key, label) ->
            memory.objects[key]?.let { label to it }
        }
        if (counted.isNotEmpty()) {
            InfoRow("Objects", counted.joinToString(" · ") { (label, n) -> "$n $label" })
            ToolNote(
                "Activities and views that keep climbing after you leave a screen usually mean " +
                    "something is holding on to it."
            )
        }
    }
}

@Composable
private fun Total(label: String, kb: Long?) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(kb?.let { MemInfo.size(it) } ?: "—", style = MaterialTheme.typography.titleLarge)
    }
}

private suspend fun read(context: android.content.Context, packageName: String): MemoryReading =
    when (val result = ShellRuns.execute(context, listOf(MemInfo.command(packageName)))) {
        ShellRuns.Result.NoShell -> MemoryReading.NoShell
        is ShellRuns.Result.Failed -> MemoryReading.Failed(result.message)
        is ShellRuns.Result.Ran ->
            if (!result.result.succeeded) {
                MemoryReading.Failed(
                    result.result.stderr.ifBlank { "dumpsys meminfo didn't answer." }
                )
            } else {
                MemInfo.parse(result.result.stdout).let {
                    if (it.isEmpty()) MemoryReading.NotRunning else MemoryReading.Running(it)
                }
            }
    }

private const val REFRESH_MILLIS = 2_000L

/** The Objects counts worth a glance, and what to call them. */
private val OBJECTS =
    listOf("Activities" to "activities", "Views" to "views", "AppContexts" to "app contexts")
