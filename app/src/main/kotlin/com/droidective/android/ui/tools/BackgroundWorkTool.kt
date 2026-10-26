package com.droidective.android.ui.tools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.droidective.core.apps.Alarms
import com.droidective.core.apps.Doze
import com.droidective.core.apps.Jobs
import com.droidective.core.apps.ScheduledAlarm
import com.droidective.core.apps.ScheduledJob
import com.droidective.core.apps.StandbyBucket
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What holds an app's background work back, put where a test needs it: Doze for the whole phone,
 * and for one app its standby bucket and the jobs it has scheduled, each runnable now. A test that
 * forces Doze should end with Wake up, which hands the phone back to Android.
 */
@Composable
fun BackgroundWorkTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    var reads by remember { mutableIntStateOf(0) }
    var deep by remember { mutableStateOf<String?>(null) }
    var light by remember { mutableStateOf<String?>(null) }
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var bucket by remember { mutableStateOf<StandbyBucket?>(null) }
    var jobs by remember { mutableStateOf<List<ScheduledJob>?>(null) }
    var alarms by remember { mutableStateOf<List<ScheduledAlarm>?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(commands: List<ShellCommand>, done: String) {
        scope.launch {
            tool.say(runAll(context, commands) ?: done)
            reads++
        }
    }
    // Forced Doze holds every app's work back until reboot; if the app goes before Wake up, its
    // next start wakes the phone.
    fun force(commands: List<ShellCommand>, done: String) {
        scope.launch {
            context.droidective.putBacks.hold(Doze.putBack)
            run(commands, done)
        }
    }
    fun wake() {
        scope.launch {
            tool.say(runAll(context, listOf(Doze.unforce, Doze.resetBattery)) ?: "Awake again")
            context.droidective.putBacks.release(Doze.putBack.id)
            reads++
        }
    }

    LaunchedEffect(reads, access.shell) {
        if (access.shell == null) return@LaunchedEffect
        deep = shellOutput(context, Doze.deepState)?.let(Doze::state)
        light = shellOutput(context, Doze.lightState)?.let(Doze::state)
    }
    LaunchedEffect(packageName, reads, access.shell) {
        val pkg = packageName ?: return@LaunchedEffect
        if (access.shell == null) return@LaunchedEffect
        bucket = shellOutput(context, Doze.getBucket(pkg))?.let(Doze::bucket)
        jobs = shellOutput(context, Jobs.list(pkg))?.let(Jobs::parse)
        alarms =
            shellOutput(context, Alarms.list)?.let {
                withContext(Dispatchers.Default) { Alarms.parse(it, pkg) }
            }
    }

    ToolScaffold("Background Work", tool) {
        if (access.shell == null) {
            ToolNote("Doze, standby buckets and jobs are reached as the shell user.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        ToolSection("Doze") {
            InfoRow("Deep", deep ?: "…", mono = true)
            InfoRow("Light", light ?: "…", mono = true)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { force(listOf(Doze.unplug, Doze.forceDeep), done = "Dozing") }) {
                    Text("Doze now")
                }
                OutlinedButton(
                    onClick = { force(listOf(Doze.unplug, Doze.step), done = "One step on") }
                ) {
                    Text("Step")
                }
                OutlinedButton(onClick = ::wake) { Text("Wake up") }
            }
            ToolNote(
                "Doze now makes Android believe the phone is unplugged and goes straight to deep " +
                    "Doze, where jobs, alarms and the network wait for a maintenance window. " +
                    "Wake up puts the battery and Doze back in Android's hands."
            )
        }
        ToolSection("App") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(packageName?.let { CrashAlerts.label(context, it) } ?: "No app picked")
                    Text(
                        packageName ?: "Its standby bucket and its scheduled jobs",
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
        ToolSection("Standby bucket") {
            InfoRow("Now", bucket?.title ?: "…")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StandbyBucket.settable.forEach { target ->
                    FilterChip(
                        selected = bucket == target,
                        onClick = { run(listOf(Doze.setBucket(pkg, target)), done = target.title) },
                        label = { Text(target.title) },
                    )
                }
            }
            ToolNote(
                "Rarer buckets run the app's jobs and alarms less often; Restricted, about once " +
                    "a day. Using the app moves it back to Active."
            )
        }
        Jobs(context, pkg, jobs) { job ->
            run(listOf(Jobs.run(pkg, job)), done = "Running job ${job.id}")
        }
        Alarms(context, pkg, alarms)
    }

    if (choosing) {
        AppChooser("Which app", onDismiss = { choosing = false }) {
            packageName = it.packageName
            bucket = null
            jobs = null
            alarms = null
            choosing = false
        }
    }
}

@Composable
private fun Jobs(
    context: Context,
    packageName: String,
    jobs: List<ScheduledJob>?,
    onRun: (ScheduledJob) -> Unit,
) {
    ToolSection("Scheduled jobs") {
        when {
            jobs == null -> ToolNote("Reading…")
            jobs.isEmpty() ->
                ToolNote("${CrashAlerts.label(context, packageName)} has no jobs scheduled now.")
            else ->
                jobs.forEach { job ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(4.dp)) {
                            Text(
                                job.tag ?: job.service.substringAfterLast('.'),
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                listOfNotNull(
                                        "#${job.id}",
                                        if (job.ready) "ready"
                                        else
                                            job.waitingFor
                                                .takeIf { it.isNotEmpty() }
                                                ?.let {
                                                    "waiting for ${it.joinToString().lowercase()}"
                                                },
                                        job.runsAt?.let { "earliest $it" },
                                    )
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onRun(job) }) { Text("Run now") }
                    }
                }
        }
    }
}

@Composable
private fun Alarms(context: Context, packageName: String, alarms: List<ScheduledAlarm>?) {
    ToolSection("Alarms") {
        when {
            alarms == null -> ToolNote("Reading…")
            alarms.isEmpty() ->
                ToolNote("${CrashAlerts.label(context, packageName)} has no alarms set now.")
            else ->
                alarms.forEach { alarm ->
                    Column(Modifier.padding(4.dp)) {
                        Text(alarm.tag.ifEmpty { alarm.type }, fontFamily = FontFamily.Monospace)
                        Text(
                            listOfNotNull(
                                    Alarms.readable(alarm.inTime).let {
                                        if (it.first().isDigit()) "in $it" else it
                                    },
                                    if (alarm.wakesThePhone) "wakes the phone" else null,
                                    if (alarm.exact) "exact" else "within a window",
                                    alarm.repeatMillis
                                        .takeIf { it > 0 }
                                        ?.let { "repeats every ${it / MILLIS_PER_MINUTE} min" },
                                )
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
        ToolNote(
            "Doze holds alarms back, except exact ones the app is allowed to set; Doze now above " +
                "shows which of them still fire."
        )
    }
}

private const val MILLIS_PER_MINUTE = 60_000L

/** Runs [commands] in order; null when they all worked, else what the failing one said. */
private suspend fun runAll(context: Context, commands: List<ShellCommand>): String? {
    val shell = context.droidective.access.shell() ?: return "Shell isn't running."
    return try {
        commands.firstNotNullOfOrNull { command ->
            val result = shell.run(command)
            if (result.succeeded) null
            else Parse.failure(result.stderr, result.stdout) ?: "It exited with ${result.exitCode}."
        }
    } catch (e: ShellUnavailableException) {
        e.message ?: "The shell stopped responding."
    }
}
