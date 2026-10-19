package com.droidective.android.ui.tools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.ui.automations.AppChooser
import com.droidective.core.apps.DevHost
import com.droidective.core.apps.ReactNative
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellResult
import com.droidective.core.shell.ShellUnavailableException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Mac's React Native hub on the phone, for one debug build: reload its JS, open its dev menu,
 * restart it, kill it in the background to test restoring state, and point it at a Metro dev
 * server. Reload JS and Dev menu are toggles too, for a tile that acts on whatever app is open.
 */
@Composable
fun ReactNativeTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    var packageName by rememberSaveable { mutableStateOf(rememberedApp(context)) }
    var choosing by remember { mutableStateOf(false) }
    var devHost by remember(packageName) { mutableStateOf<String?>(null) }
    var typed by rememberSaveable(packageName) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> String) {
        scope.launch {
            tool.say(
                try {
                    block()
                } catch (e: IllegalStateException) {
                    e.message ?: "It didn't work."
                }
            )
        }
    }

    LaunchedEffect(packageName, access.shell) {
        val pkg = packageName ?: return@LaunchedEffect
        if (access.shell == null) return@LaunchedEffect
        devHost = runCatching { readDevHost(context, pkg) }.getOrNull()
    }

    ToolScaffold("React Native", tool) {
        if (access.shell == null) {
            ToolNote("Sending keys to an app and reaching a debug build's settings need Shell.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        ToolSection("App") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(packageName?.let { CrashAlerts.label(context, it) } ?: "No app picked")
                    Text(
                        packageName ?: "Your app's debug build",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (packageName != null) FontFamily.Monospace else null,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { choosing = true }) {
                    Text(if (packageName == null) "Pick an app" else "Change")
                }
            }
        }
        val pkg = packageName ?: return@ToolScaffold
        ToolSection("Do") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { run { inFront(context, pkg, ReactNative.reload) } }) {
                    Text("Reload JS")
                }
                OutlinedButton(onClick = { run { inFront(context, pkg, ReactNative.devMenu) } }) {
                    Text("Open dev menu")
                }
                OutlinedButton(onClick = { run { restart(context, pkg) } }) { Text("Restart") }
                OutlinedButton(onClick = { run { processDeath(context, pkg) } }) {
                    Text("Simulate process death")
                }
            }
            ToolNote(
                "Each brings the app to the front first. Process death kills it while it's in the " +
                    "background, the way Android does to free memory; open it again to see what " +
                    "it restores."
            )
        }
        ToolSection("Dev server") {
            InfoRow(
                "Now",
                devHost ?: "Not set: the build's own, usually localhost:8081",
                mono = true,
            )
            OutlinedTextField(
                typed,
                { typed = it },
                label = { Text("Metro, as host:port") },
                placeholder = { Text("192.168.1.10:8081") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = typed.isNotBlank(),
                onClick = {
                    run {
                        val host =
                            ReactNative.parseHost(typed).getOrElse { error(it.message.orEmpty()) }
                        pointAt(context, pkg, host).also { devHost = host.toString() }
                    }
                },
            ) {
                Text("Point the app here and restart it")
            }
            ToolNote(
                "Use the address of the computer running Metro. localhost works only with " +
                    "adb reverse from that computer."
            )
        }
        ToolSection("From a tile") {
            ToolNote(
                "Reload JS and Dev menu are also toggles. Put them on a Quick Settings tile or " +
                    "the notification bar, and they act on whatever app is open under the shade."
            )
        }
    }

    if (choosing) {
        AppChooser(
            title = "Which debug build",
            onDismiss = { choosing = false },
            only = { it.debuggable },
            empty =
                "No debug builds are installed. Install your app's debug build, the one Metro " +
                    "serves.",
        ) {
            packageName = it.packageName
            rememberApp(context, it.packageName)
            choosing = false
        }
    }
}

/** Brings the app to the front, waits for it to draw, then sends [keys]. */
private suspend fun inFront(context: Context, packageName: String, keys: ShellCommand): String {
    launch(context, packageName)
    delay(FRONT_MILLIS)
    ensure(shell(context, keys))
    return "Sent to ${CrashAlerts.label(context, packageName)}"
}

private suspend fun restart(context: Context, packageName: String): String {
    ensure(shell(context, ReactNative.forceStop(packageName)))
    launch(context, packageName)
    return "Restarted ${CrashAlerts.label(context, packageName)}"
}

/** The Mac's process death: kill it in the background and check Android let it go. */
private suspend fun processDeath(context: Context, packageName: String): String {
    val label = CrashAlerts.label(context, packageName)
    ensure(shell(context, ReactNative.kill(packageName)))
    delay(SETTLE_MILLIS)
    val alive = shell(context, ReactNative.pidOf(packageName)).stdout.isNotBlank()
    return if (alive) {
        "$label is still running: something keeps it alive, such as a foreground service. " +
            "Force stop it from Apps instead."
    } else {
        "Killed $label in the background. Open it again to see what it restores."
    }
}

/**
 * Writes RN's `debug_http_host` the way the Mac does, then restarts the app so it reads it: the
 * running app keeps its preferences in memory.
 */
private suspend fun pointAt(context: Context, packageName: String, host: DevHost): String {
    val existing = shell(context, ReactNative.readPreferences(packageName))
    if (!existing.succeeded && "not debuggable" in existing.stderr) {
        error("This app isn't a debug build, so its dev server can't be set from here.")
    }
    val xml = ReactNative.withDevHost(existing.stdout.takeIf { existing.succeeded }, host)
    ensure(shell(context, ReactNative.writePreferences(packageName, xml)))
    val written =
        ReactNative.devHostIn(shell(context, ReactNative.readPreferences(packageName)).stdout)
    if (written != host.toString())
        error("The setting didn't stick. Set it from the dev menu instead.")
    ensure(shell(context, ReactNative.forceStop(packageName)))
    launch(context, packageName)
    return "Pointed at $host and restarted it"
}

private suspend fun readDevHost(context: Context, packageName: String): String? {
    val result = shell(context, ReactNative.readPreferences(packageName))
    return if (result.succeeded) ReactNative.devHostIn(result.stdout) else null
}

private suspend fun launch(context: Context, packageName: String) {
    val result = shell(context, ReactNative.launch(packageName))
    if (!ReactNative.launched(result.stdout, result.stderr)) {
        error("${CrashAlerts.label(context, packageName)} has no screen to open.")
    }
}

private suspend fun shell(context: Context, command: ShellCommand): ShellResult {
    val shell = context.droidective.access.shell() ?: error("Shell isn't running.")
    return try {
        shell.run(command)
    } catch (e: ShellUnavailableException) {
        error(e.message ?: "The shell stopped responding.")
    }
}

/** Fails with the command's own words when it did. */
private fun ensure(result: ShellResult) {
    if (result.succeeded) return
    error(
        Parse.lines(result.stderr.ifBlank { result.stdout }).firstOrNull { it.isNotBlank() }?.trim()
            ?: "It exited with ${result.exitCode}."
    )
}

private fun rememberedApp(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APP, null)

private fun rememberApp(context: Context, packageName: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putString(KEY_APP, packageName)
    }
}

private const val PREFS = "react-native"
private const val KEY_APP = "app"
private const val FRONT_MILLIS = 1_500L
private const val SETTLE_MILLIS = 600L
