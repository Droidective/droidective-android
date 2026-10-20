package com.droidective.android.ui.tools

import android.content.Intent
import android.provider.Settings
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
import com.droidective.android.ui.openSafely
import com.droidective.core.apps.NotificationInspector
import com.droidective.core.apps.NotificationSettings
import com.droidective.core.apps.PostedNotification
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Why a notification shows or doesn't: what an app has showing now, each channel's importance,
 * whether its notifications are off as a whole, and Do Not Disturb. Only the app picked is read out
 * of the dump, which holds every app's notification text.
 */
@Composable
fun NotificationInspectorTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var reads by remember { mutableIntStateOf(0) }
    var posted by remember { mutableStateOf<List<PostedNotification>?>(null) }
    var settings by remember { mutableStateOf<NotificationSettings?>(null) }
    LaunchedEffect(packageName, reads, access.shell) {
        val pkg = packageName ?: return@LaunchedEffect
        val dump = shellOutput(context, NotificationInspector.dump) ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            posted = NotificationInspector.posted(dump, pkg)
            settings = NotificationInspector.settings(dump, pkg)
        }
    }

    ToolScaffold("Notification Inspector", tool) {
        if (access.shell == null) {
            ToolNote("Android shows the notification records to the shell user.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        ToolSection("App") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(packageName?.let { CrashAlerts.label(context, it) } ?: "No app picked")
                    Text(
                        packageName ?: "What it has showing, and its channels",
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
        val now = settings
        if (now == null) {
            ToolNote("Reading…")
            return@ToolScaffold
        }
        Blockers(now)
        Showing(posted.orEmpty())
        Channels(now)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { reads++ }) { Text("Read again") }
            TextButton(
                onClick = {
                    context.openSafely(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    )
                }
            ) {
                Text("Its notification settings")
            }
        }
    }

    if (choosing) {
        AppChooser("Which app", onDismiss = { choosing = false }) {
            packageName = it.packageName
            posted = null
            settings = null
            choosing = false
        }
    }
}

/** What would stop a notification showing at all, said first. */
@Composable
private fun Blockers(settings: NotificationSettings) {
    val problems =
        listOfNotNull(
            "Its notifications are turned off.".takeIf { settings.blocked },
            NotificationInspector.doNotDisturb(settings.doNotDisturb),
            "Every one of its channels is off."
                .takeIf {
                    settings.channels.isNotEmpty() && settings.channels.all { it.importance == 0 }
                },
        )
    problems.forEach {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun Showing(posted: List<PostedNotification>) {
    val dates = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    ToolSection("Showing now (${posted.size})") {
        if (posted.isEmpty()) ToolNote("Nothing in the shade from it right now.")
        posted.forEach { notification ->
            Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Text(notification.title ?: "(no title)")
                notification.text?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                Text(
                    listOfNotNull(
                            "channel ${notification.channel}",
                            "id ${notification.id}" + (notification.tag?.let { ", tag $it" } ?: ""),
                            notification.postedAt?.let { "posted ${dates.format(Date(it))}" },
                            "ongoing".takeIf { notification.ongoing },
                            notification.actions
                                .takeIf { it.isNotEmpty() }
                                ?.let { "actions: ${it.joinToString()}" },
                        )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Channels(settings: NotificationSettings) {
    ToolSection("Channels (${settings.channels.size})") {
        if (settings.channels.isEmpty()) {
            ToolNote("It hasn't made any channels yet: it makes them the first time it posts.")
        }
        settings.channels.forEach { channel ->
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text(channel.name)
                Text(
                    listOfNotNull(
                            channel.id,
                            NotificationInspector.importance(channel.importance),
                            "skips Do Not Disturb".takeIf { channel.bypassesDnd },
                        )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color =
                        if (channel.importance == 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
