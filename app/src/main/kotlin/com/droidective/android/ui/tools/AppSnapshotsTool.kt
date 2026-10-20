package com.droidective.android.ui.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.ui.automations.AppChooser
import com.droidective.core.Access
import com.droidective.core.apps.AppSnapshot
import com.droidective.core.apps.AppSnapshots
import com.droidective.core.apps.MemInfo
import com.droidective.core.apps.SnapshotAccess
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.shell.Stoppable
import com.droidective.core.shell.Terminal
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saved copies of an app's private data, to put back later: a signed-in or onboarded state a test
 * can start from again and again. A debug build is reached as itself through run-as; any other app
 * needs root.
 */
@Composable
fun AppSnapshotsTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    var snapshots by remember { mutableStateOf(emptyList<Pair<AppSnapshot, Long>>()) }
    var restoring by remember { mutableStateOf<AppSnapshot?>(null) }
    val app = remember(packageName) { packageName?.let { info(context, it) } }
    val how = app?.let { method(it, access) }
    val asRoot = access.shell?.isRoot == true
    LaunchedEffect(packageName, reads) {
        snapshots = withContext(Dispatchers.IO) { packageName?.let { list(context, it) }.orEmpty() }
    }
    fun run(working: String, done: String, failed: () -> Unit = {}, command: () -> ShellCommand) {
        busy = working
        scope.launch {
            val problem = runLong(context, command())
            if (problem != null) withContext(Dispatchers.IO) { failed() }
            tool.say(problem ?: done)
            busy = null
            reads++
        }
    }

    ToolScaffold("App Snapshots", tool) {
        if (access.shell == null) {
            ToolNote("Snapshots are taken as the shell user, through run-as or root.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        ToolSection("App") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(packageName?.let { CrashAlerts.label(context, it) } ?: "No app picked")
                    packageName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        when {
                            packageName == null -> "Its private data: files, preferences, databases"
                            how == SnapshotAccess.RunAs -> "A debug build, reached through run-as"
                            how == SnapshotAccess.Root -> "Reached as root"
                            else -> "Needs root, or a debug build of the app"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { choosing = true }) {
                    Text(if (packageName == null) "Pick an app" else "Change")
                }
            }
        }
        val pkg = packageName ?: return@ToolScaffold
        val target = app ?: return@ToolScaffold
        val method = how ?: return@ToolScaffold
        busy?.let { ToolNote(it) }
        Button(
            enabled = busy == null,
            onClick = {
                val file =
                    File(folder(context), AppSnapshots.fileName(pkg, System.currentTimeMillis()))
                run("Saving…", "Snapshot saved", failed = { file.delete() }) {
                    AppSnapshots.save(pkg, method, target.dataDir, file.path, asRoot)
                }
            },
        ) {
            Text("Save a snapshot")
        }
        Snapshots(
            snapshots,
            enabled = busy == null,
            onRestore = { restoring = it },
            onDelete = { snapshot ->
                File(folder(context), snapshot.fileName).delete()
                reads++
            },
        )
        ToolNote(
            "The cache is left out. Restoring stops the app and replaces everything it has saved " +
                "since, so it starts from exactly that moment."
        )
    }

    if (choosing) {
        AppChooser("Which app", onDismiss = { choosing = false }) {
            packageName = it.packageName
            choosing = false
        }
    }
    restoring?.let { snapshot ->
        val target = app
        val method = how
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Restore this snapshot?") },
            text = {
                Text(
                    "${CrashAlerts.label(context, snapshot.packageName)} is stopped, and its data " +
                        "is replaced with the copy from ${stamp(snapshot.takenAt)}. What it saved " +
                        "since is lost."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoring = null
                        if (target == null || method == null) return@TextButton
                        val path = File(folder(context), snapshot.fileName).path
                        run("Restoring…", "Restored") {
                            AppSnapshots.restore(
                                snapshot.packageName,
                                method,
                                target.dataDir,
                                path,
                                target.uid,
                                asRoot,
                            )
                        }
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Snapshots(
    snapshots: List<Pair<AppSnapshot, Long>>,
    enabled: Boolean,
    onRestore: (AppSnapshot) -> Unit,
    onDelete: (AppSnapshot) -> Unit,
) {
    ToolSection("Snapshots") {
        if (snapshots.isEmpty()) ToolNote("None yet.")
        snapshots.forEach { (snapshot, bytes) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(4.dp)) {
                    Text(stamp(snapshot.takenAt))
                    Text(
                        MemInfo.size(bytes / BYTES_PER_KB),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(enabled = enabled, onClick = { onRestore(snapshot) }) { Text("Restore") }
                TextButton(enabled = enabled, onClick = { onDelete(snapshot) }) { Text("Delete") }
            }
        }
    }
}

private fun info(context: Context, packageName: String): ApplicationInfo? =
    try {
        context.packageManager.getApplicationInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

/** run-as for a debug build, which needs nothing more; otherwise root, if there is root. */
private fun method(app: ApplicationInfo, access: Access): SnapshotAccess? =
    when {
        app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 -> SnapshotAccess.RunAs
        access.shell?.isRoot == true -> SnapshotAccess.Root
        else -> null
    }

/**
 * This app's own folder on shared storage: the shell and root can write it, other apps can't read
 * it.
 */
private fun folder(context: Context): File =
    File(context.getExternalFilesDir(null), "snapshots").apply { mkdirs() }

private fun list(context: Context, packageName: String): List<Pair<AppSnapshot, Long>> =
    folder(context)
        .listFiles()
        .orEmpty()
        .mapNotNull { file -> AppSnapshots.parse(file.name)?.let { it to file.length() } }
        .filter { (snapshot, _) -> snapshot.packageName == packageName }
        .sortedByDescending { (snapshot, _) -> snapshot.takenAt }

/**
 * Runs [command] to the end without the shell's 30-second limit, since a large app's data takes
 * longer to pack. Null when it worked, else what went wrong.
 */
private suspend fun runLong(context: Context, command: ShellCommand): String? {
    val shell = context.droidective.access.shell() ?: return "Shell isn't running."
    var exit: Int? = null
    var last: String? = null
    return try {
        shell.stream(Terminal.wrap(command.line, Terminal.HOME)).collect { line ->
            val ending = Terminal.ending(line)
            if (ending != null) exit = ending.exitCode
            else if (line.isNotBlank() && Stoppable.pid(line) == null) last = line.trim()
        }
        if (exit == 0) null else last ?: "It stopped with ${exit ?: "no exit code"}."
    } catch (e: ShellUnavailableException) {
        e.message ?: "The shell stopped responding."
    }
}

private fun stamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private const val BYTES_PER_KB = 1024L
