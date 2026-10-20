package com.droidective.android.ui.tools

import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import com.droidective.core.apps.AppLinkStatus
import com.droidective.core.apps.AppLinks

/**
 * Why a link opens the browser instead of the app: each domain an app declares for App Links, where
 * Android's verification of it stands, and the commands that move it.
 */
@Composable
fun AppLinksSection(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val shell = rememberToolShell(tool)
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<AppLinkStatus?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    val user = remember { Process.myUid() / PER_USER_RANGE }
    LaunchedEffect(packageName, reads, access.shell) {
        val pkg = packageName ?: return@LaunchedEffect
        status = shellOutput(context, AppLinks.status(pkg, user))?.let(AppLinks::parse)
    }

    ToolSection("App Links") {
        if (access.shell == null) {
            ToolNote("Android shows an app's link verification to the shell user.")
            return@ToolSection
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(packageName?.let { CrashAlerts.label(context, it) } ?: "No app picked")
                Text(
                    packageName ?: "Its verified domains, for links that should open it",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (packageName != null) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { choosing = true }) {
                Text(if (packageName == null) "Pick an app" else "Change")
            }
        }
        val pkg = packageName ?: return@ToolSection
        val now = status
        when {
            now == null -> ToolNote("Reading…")
            now.domains.isEmpty() ->
                ToolNote(
                    "It declares no App Links: no intent filter with autoVerify and an https " +
                        "host."
                )
            else -> Domains(now)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    shell.run("Verify again", listOf(AppLinks.reverify(pkg)), "Asked again") {
                        reads++
                    }
                }
            ) {
                Text("Verify again")
            }
            TextButton(
                onClick = {
                    shell.run("Approve", listOf(AppLinks.approve(pkg)), "Approved for testing") {
                        reads++
                    }
                }
            ) {
                Text("Approve for testing")
            }
            TextButton(
                onClick = {
                    shell.run("Reset", AppLinks.reset(pkg), "Reset and asked again") { reads++ }
                }
            ) {
                Text("Reset")
            }
            TextButton(onClick = { reads++ }) { Text("Read again") }
        }
        ToolNote(
            "The verifier answers in the background: read again after a few seconds. Approve " +
                "makes a debug build's links open it without an assetlinks.json; Reset undoes it."
        )
    }

    if (choosing) {
        AppChooser("Which app", onDismiss = { choosing = false }) {
            packageName = it.packageName
            status = null
            choosing = false
        }
    }
}

@Composable
private fun Domains(status: AppLinkStatus) {
    status.domains.forEach { domain ->
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(domain.domain, fontFamily = FontFamily.Monospace)
            Text(
                domain.meaning,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (domain.opensTheApp) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            )
        }
    }
    if (status.handlingAllowed == false) {
        ToolNote("Opening supported links is turned off for this app in its settings.")
    }
}

private const val PER_USER_RANGE = 100_000
