package com.droidective.android.ui.tools

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.monkey.MonkeyPlan
import com.droidective.android.monkey.MonkeyRun
import com.droidective.android.monkey.MonkeyRuns
import com.droidective.android.ui.automations.AppChooser
import com.droidective.android.ui.notificationsShow
import com.droidective.android.ui.openNotificationSettings
import com.droidective.android.ui.openSafely
import com.droidective.core.apps.AppLocales
import com.droidective.core.apps.Monkey
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellUnavailableException
import kotlin.random.Random
import kotlinx.coroutines.launch

/** The app a testing tool is pointed at, with the button that changes it. */
@Composable
private fun TestedApp(packageName: String?, hint: String, onPick: () -> Unit) {
    val context = LocalContext.current
    ToolSection("App") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(packageName?.let { CrashAlerts.label(context, it) } ?: "No app picked")
                Text(
                    packageName ?: hint,
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
 * The Mac's Change Locale, for one app at a time: Android 13's per-app languages, set through the
 * locale service, so an app can be tested in Arabic or a pseudo-locale while the phone stays in its
 * own language.
 */
@Composable
fun LanguageTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var current by remember(packageName) { mutableStateOf<List<String>?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    var custom by rememberSaveable { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun set(tags: List<String>) {
        val pkg = packageName ?: return
        scope.launch {
            val result = runShell(context, AppLocales.set(pkg, tags))
            tool.say(
                result.fold(
                    onSuccess = {
                        reads++
                        if (tags.isEmpty()) "${CrashAlerts.label(context, pkg)} follows the phone"
                        else "${CrashAlerts.label(context, pkg)}: ${tags.joinToString()}"
                    },
                    onFailure = { it.message ?: "It didn't change." },
                )
            )
        }
    }

    LaunchedEffect(packageName, reads, access.shell) {
        val pkg = packageName ?: return@LaunchedEffect
        current = runShell(context, AppLocales.get(pkg)).getOrNull()?.let(AppLocales::parse)
    }

    ToolScaffold("Language", tool) {
        if (Build.VERSION.SDK_INT < AppLocales.MIN_SDK) {
            ToolNote(
                "Each app's own language arrived in Android 13; this phone runs Android " +
                    "${Build.VERSION.RELEASE}. The phone's language is in Settings."
            )
            OpenLanguageSettings(context)
            return@ToolScaffold
        }
        if (access.shell == null) {
            ToolNote("Setting another app's language needs Shell, through Shizuku or root.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        TestedApp(packageName, "Any app, in a language of its own") { choosing = true }
        val pkg = packageName ?: return@ToolScaffold
        ToolSection("Now") {
            InfoRow(
                "Language",
                when (val now = current) {
                    null -> "Reading…"
                    emptyList<String>() -> "The phone's"
                    else -> now.joinToString()
                },
                mono = current?.isNotEmpty() == true,
            )
            OutlinedButton(
                enabled = current?.isNotEmpty() == true,
                onClick = { set(emptyList()) },
            ) {
                Text("Follow the phone's language")
            }
        }
        ToolSection("Switch to") {
            AppLocales.common.forEach { language ->
                val chosen = current?.firstOrNull() == language.tag
                Row(
                    Modifier.fillMaxWidth().clickable { set(listOf(language.tag)) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(language.name)
                        language.note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        language.tag,
                        fontFamily = FontFamily.Monospace,
                        color =
                            if (chosen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                custom,
                { custom = it.trim() },
                label = { Text("Another, such as nl-BE or zh-Hant-TW") },
                singleLine = true,
                isError = custom.isNotEmpty() && !AppLocales.isTag(custom),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(enabled = AppLocales.isTag(custom), onClick = { set(listOf(custom)) }) {
                Text("Use it")
            }
            ToolNote(
                "The app redraws in its new language at once. Pseudo-locales show only in a " +
                    "build made with them, as Android Studio's debug builds can be."
            )
        }
        OpenLanguageSettings(context)
    }

    if (choosing) {
        AppChooser("Which app", onDismiss = { choosing = false }) {
            packageName = it.packageName
            choosing = false
        }
    }
}

@Composable
private fun OpenLanguageSettings(context: Context) {
    OutlinedButton(onClick = { context.openSafely(Intent(Settings.ACTION_LOCALE_SETTINGS)) }) {
        Text("The phone's language")
    }
}

/**
 * The Mac's Monkey Test: random taps, swipes and typing in one app until it crashes or the count
 * runs out, with the seed shown so a crash can be run again exactly.
 */
@Composable
fun MonkeyTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var countText by rememberSaveable { mutableStateOf(DEFAULT_COUNT.toString()) }
    val run by MonkeyRuns.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<Long?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var swipes by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val count = countText.toIntOrNull()?.takeIf { it in Monkey.COUNTS }

    fun start(seed: Long) {
        val pkg = packageName ?: return
        val events = count ?: return
        MonkeyRuns.start(context, MonkeyPlan(pkg, events, seed, swipes))
    }

    ToolScaffold("Monkey Test", tool) {
        if (access.shell == null) {
            ToolNote("Monkey sends its events as the shell user, through Shizuku or root.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        TestedApp(packageName, "Your app, on a test build or a test account") { choosing = true }
        val pkg = packageName ?: return@ToolScaffold
        ToolSection("Run") {
            OutlinedTextField(
                countText,
                { countText = it.filter(Char::isDigit).take(6) },
                label = { Text("Events") },
                singleLine = true,
                isError = count == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 500, 2_000, 10_000).forEach { n ->
                    FilterChip(
                        selected = count == n,
                        onClick = { countText = n.toString() },
                        label = { Text("$n") },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Swipes too")
                    Text(
                        if (swipes) {
                            "Scrolls and drags reach the whole screen: a swipe from the top " +
                                "opens Quick Settings, and the next taps can switch Bluetooth " +
                                "off or turn the brightness down."
                        } else {
                            "Taps only, which can't open the shade or Quick Settings."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (swipes) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = swipes, onCheckedChange = { swipes = it })
            }
            if (run !is MonkeyRun.Running) {
                Button(
                    enabled = count != null,
                    onClick = { confirming = Random.nextLong(1, SEED_MAX) },
                ) {
                    Text("Start")
                }
            } else {
                OutlinedButton(onClick = { MonkeyRuns.stop(context) }) { Text("Stop") }
            }
            if (!notificationsShow(context)) {
                ToolNote(
                    "While Monkey runs it keeps every other app from opening, this one included, " +
                        "and its notification has the Stop button. Notifications are off for " +
                        "Droidective, so allow them first."
                )
                OutlinedButton(onClick = { openNotificationSettings(context) }) {
                    Text("Allow notifications")
                }
            }
        }
        when (val now = run) {
            null ->
                ToolNote("It starts the app and sends it random events, $THROTTLE between each.")
            is MonkeyRun.Running -> {
                if (now.events > 0) {
                    LinearProgressIndicator(
                        progress = { now.events / now.plan.count.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ToolNote("${now.events} of ${now.plan.count} events · seed ${now.plan.seed}")
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    ToolNote("${now.plan.count} events · seed ${now.plan.seed}")
                }
            }
            is MonkeyRun.Failed -> ToolNote(now.message)
            is MonkeyRun.Done -> MonkeyResult(now, onReplay = { confirming = now.seed })
        }
    }

    confirming?.let { seed ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text("Let Monkey loose in ${CrashAlerts.label(context, packageName.orEmpty())}?")
            },
            text = {
                Text(
                    (if (swipes) "It taps and swipes" else "It taps") +
                        " at random, and can change or delete anything the app lets a person " +
                        "change. Run it on a test build or a test account."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        start(seed)
                    }
                ) {
                    Text("Start")
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }

    if (choosing) {
        AppChooser("Which app", onDismiss = { choosing = false }) {
            packageName = it.packageName
            choosing = false
        }
    }
}

@Composable
private fun MonkeyResult(done: MonkeyRun.Done, onReplay: () -> Unit) {
    val report = done.report
    ToolSection(
        when {
            report.crashed -> "It crashed"
            report.notResponding -> "It stopped responding"
            else -> "No crash"
        }
    ) {
        InfoRow("Events sent", report.injected?.toString())
        InfoRow("Seed", done.seed.toString(), copyable = true, mono = true)
        report.crash?.let { InfoRow("Exception", it, copyable = true, mono = true) }
        if (report.crashed || report.notResponding) {
            ToolNote(
                "The same seed sends the same events, so it should fail the same way again. " +
                    "Crash Catcher has the full report."
            )
            OutlinedButton(onClick = onReplay) { Text("Run that seed again") }
        }
    }
}

/** Streams a run, counting the events it sends, and reads its report at the end. */
private suspend fun runShell(
    context: Context,
    command: com.droidective.core.shell.ShellCommand,
): Result<String> {
    val shell =
        context.droidective.access.shell()
            ?: return Result.failure(IllegalStateException("Shell isn't running."))
    return try {
        val result = shell.run(command)
        if (result.succeeded) Result.success(result.stdout)
        else
            Result.failure(
                IllegalStateException(
                    Parse.lines(result.stderr.ifBlank { result.stdout }).firstOrNull {
                        it.isNotBlank()
                    } ?: "It exited with ${result.exitCode}."
                )
            )
    } catch (e: ShellUnavailableException) {
        Result.failure(IllegalStateException(e.message ?: "The shell stopped responding."))
    }
}

private const val DEFAULT_COUNT = 500
private const val PROGRESS_EVERY = 10
private const val SEED_MAX = 1_000_000_000L
private const val THROTTLE = "${Monkey.THROTTLE_MILLIS} ms"
