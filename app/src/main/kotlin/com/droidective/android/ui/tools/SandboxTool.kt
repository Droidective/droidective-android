package com.droidective.android.ui.tools

import android.content.Context
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.ui.automations.AppChooser
import com.droidective.core.apps.SandboxAccess
import com.droidective.core.apps.SandboxSource

/**
 * The Mac's Sandbox Browser on the phone: an app's private files, its SharedPreferences as a table
 * and its databases as tables you can query. A debug build is reached through `run-as`, which the
 * shell user can do; any app, with root. Databases are read from a copy, so nothing here writes to
 * the app.
 */
@Composable
fun SandboxTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    // Shizuku started by root is root too: either reaches any app's folder.
    val rooted = access.shell?.isRoot == true
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var path by rememberSaveable { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }

    ToolScaffold("Sandbox Browser", tool) {
        if (access.shell == null) {
            ToolNote(
                "Reading an app's private files needs Shell, through Shizuku or root: " +
                    "Shell for debug builds, root for any app."
            )
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        AppRow(context, packageName, rooted) { choosing = true }
        val pkg = packageName ?: return@ToolScaffold
        val mode = remember(pkg, rooted) { accessFor(context, pkg, rooted) }
        val files =
            remember(pkg, mode) {
                Files(SandboxSource(pkg, mode), copies = "sandbox") { said -> explain(said, mode) }
            }
        FileBrowser(files, path, onPath = { path = it }, tool)
    }

    if (choosing) {
        AppChooser(
            title = if (rooted) "Which app" else "Which debug build",
            onDismiss = { choosing = false },
            only = { rooted || it.debuggable },
            empty =
                "No debug builds are installed. Without root, run-as reaches only an app built " +
                    "debuggable, such as your own app's debug build.",
        ) {
            packageName = it.packageName
            path = ""
            choosing = false
        }
    }
}

@Composable
private fun AppRow(context: Context, packageName: String?, rooted: Boolean, onPick: () -> Unit) {
    ToolSection("App") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(packageName?.let { CrashAlerts.label(context, it) } ?: "No app picked")
                Text(
                    packageName
                        ?: if (rooted) "Root reaches any app's files"
                        else "Shell reaches debug builds, through run-as",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (packageName != null) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onPick) {
                Text(if (packageName == null) "Pick an app" else "Change")
            }
        }
    }
}

/**
 * Root goes to the folder Android reports for the app; without root, or for an app that's gone,
 * `run-as`, which explains itself when it can't.
 */
private fun accessFor(context: Context, packageName: String, rooted: Boolean): SandboxAccess {
    if (!rooted) return SandboxAccess.RunAs
    return try {
        SandboxAccess.Root(context.packageManager.getApplicationInfo(packageName, 0).dataDir)
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        SandboxAccess.RunAs
    }
}

/** run-as says "package not debuggable", which deserves a sentence. */
private fun explain(said: String, mode: SandboxAccess): String =
    if (mode == SandboxAccess.RunAs && "not debuggable" in said) {
        "This app isn't a debug build, so run-as can't reach it. Root can."
    } else {
        said
    }
