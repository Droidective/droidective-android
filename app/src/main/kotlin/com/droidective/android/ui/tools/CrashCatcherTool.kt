package com.droidective.android.ui.tools

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.ui.SwitchRow
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.automations.AppChooser
import com.droidective.android.ui.copy
import com.droidective.android.ui.needsNotificationPermission
import com.droidective.android.ui.notificationsShow
import com.droidective.android.ui.openNotificationSettings
import com.droidective.android.ui.openSafely
import com.droidective.android.ui.openUsageAccess
import com.droidective.core.Grants
import com.droidective.core.ago
import com.droidective.core.crashes.CrashReport
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote
import com.droidective.platform.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recent crashes, native crashes and ANRs on the phone, and alerts for the apps you watch. */
@Composable
fun CrashCatcherTool(tool: ToolContext) {
    val context = LocalContext.current
    val log = remember { CrashLog(context) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    var access by remember { mutableStateOf(log.access()) }
    var reports by remember { mutableStateOf<List<CrashReport>?>(null) }
    var watched by remember { mutableStateOf(CrashAlerts.watched(context)) }
    var open by remember { mutableStateOf<CrashReport?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val focus by CrashAlerts.focus.collectAsStateWithLifecycle()
    // An alert is a notification: without the permission, watching an app does nothing visible.
    var alertsShow by remember { mutableStateOf(notificationsShow(context)) }
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            alertsShow = notificationsShow(context)
        }
    val watch = { packageName: String, on: Boolean ->
        CrashAlerts.setWatched(context, packageName, on)
        watched = CrashAlerts.watched(context)
        if (on && needsNotificationPermission(context)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(resumes) {
        alertsShow = notificationsShow(context)
        access = log.access()
        reports = withContext(Dispatchers.IO) { log.recent() }
    }
    LaunchedEffect(focus) {
        val ref = focus ?: return@LaunchedEffect
        open = withContext(Dispatchers.IO) { log.read(ref.tag, ref.timeMillis) }
        CrashAlerts.focus.value = null
    }

    open?.let { report ->
        val watching = report.packageName in watched
        CrashDetail(
            report,
            ToolContext(tool.snackbar, onBack = { open = null }, tool.onOpenSetup),
            watching = watching,
            onWatch = { watch(report.packageName, !watching) },
        )
        return
    }

    ToolScaffold("Crash Catcher", tool) {
        AccessNote(access, tool)
        ToolSection("Alerts") {
            if (watched.isEmpty()) {
                ToolNote("Watch an app to get a notification when it crashes or stops responding.")
            } else if (!alertsShow) {
                ToolNote("Notifications are off for Droidective, so these alerts can't show.")
                OutlinedButton(
                    onClick = {
                        if (needsNotificationPermission(context)) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings(context)
                        }
                    }
                ) {
                    Text("Allow notifications")
                }
            }
            watched.sorted().forEach { packageName ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(CrashAlerts.label(context, packageName))
                        Text(
                            packageName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { watch(packageName, false) }) {
                        Icon(
                            painterResource(Symbols.byName.getValue("close").outlined),
                            "Stop watching",
                        )
                    }
                }
            }
            OutlinedButton(onClick = { choosing = true }) { Text("Watch an app") }
        }
        ToolSection("Recent") {
            when {
                reports == null -> ToolNote("Reading crash reports…")
                reports.orEmpty().isEmpty() ->
                    ToolNote(
                        if (access == CrashLog.Access.Ready) {
                            "No crashes on record. Android keeps them for a few days."
                        } else {
                            "Crash reports show here once Droidective can read them."
                        }
                    )
            }
            reports.orEmpty().forEach { report -> CrashRow(report) { open = report } }
        }
    }

    if (choosing) {
        AppChooser(
            title = "Watch for crashes",
            onDismiss = { choosing = false },
            onPick = {
                watch(it.packageName, true)
                choosing = false
            },
        )
    }
}

/** What's missing before crash reports can be read, with the way to get it. */
@Composable
private fun AccessNote(access: CrashLog.Access, tool: ToolContext) {
    val context = LocalContext.current
    when (access) {
        CrashLog.Access.Ready -> Unit
        CrashLog.Access.NeedsGrant -> {
            val permission = Grants.crashReports(Build.VERSION.SDK_INT)
            val command =
                "adb shell " +
                    ShellCommand.of("pm", "grant", shellQuote(context.packageName), permission).line
            ToolNote(
                "Android keeps crash reports behind ${permission.substringAfterLast('.')}, which " +
                    "only a computer or Shell can grant. Phone setup on the Mac grants it, and " +
                    "so does Droidective itself while it has Shell. Or run this once:"
            )
            InfoRow("Command", command, copyable = true, mono = true)
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
        }
        CrashLog.Access.NeedsUsageAccess -> {
            ToolNote("Reading a crash report also needs Usage access for Droidective.")
            OutlinedButton(onClick = { context.openUsageAccess() }) { Text("Allow Usage access") }
        }
    }
}

@Composable
private fun CrashRow(report: CrashReport, onOpen: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(CrashAlerts.title(context, report), style = MaterialTheme.typography.titleSmall)
        Text(
            report.headline,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            ago(report.timeMillis, System.currentTimeMillis()).replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One report in full: what happened, to which version, and the stack, tombstone or ANR. */
@Composable
private fun CrashDetail(
    report: CrashReport,
    tool: ToolContext,
    watching: Boolean,
    onWatch: () -> Unit,
) {
    val context = LocalContext.current
    val title = CrashAlerts.title(context, report)
    val text = shareText(title, report)
    ToolScaffold(title, tool) {
        ToolSection("What happened") {
            SelectionContainer {
                Text(
                    report.headline,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            InfoRow("App", report.packageName, copyable = true, mono = true)
            InfoRow("Version", report.versionName)
            InfoRow("Process", report.process, mono = true)
            InfoRow("When", ago(report.timeMillis, System.currentTimeMillis()))
            InfoRow(
                "On screen",
                when (report.foreground) {
                    true -> "Yes"
                    false -> "No"
                    null -> null
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { context.copy("Crash report", text) }) { Text("Copy") }
            OutlinedButton(
                onClick = {
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, title)
                            .putExtra(Intent.EXTRA_TEXT, text)
                    context.openSafely(Intent.createChooser(send, "Share the crash report"))
                }
            ) {
                Text("Share")
            }
        }
        SwitchRow("Alert me when it crashes", checked = watching, onChange = { onWatch() })
        if (report.details.isNotEmpty()) {
            ToolSection("Report") {
                SelectionContainer {
                    Text(
                        report.details,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** The report as plain text, for a bug tracker or a chat. */
private fun shareText(title: String, report: CrashReport): String = buildString {
    appendLine(title)
    appendLine("Package: ${report.packageName} ${report.versionName.orEmpty()}".trimEnd())
    appendLine("Process: ${report.process}")
    appendLine()
    append(report.details.ifEmpty { report.headline })
}
