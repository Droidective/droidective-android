package com.droidective.android.ui

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.DroidectiveApplication
import com.droidective.android.crashes.CrashReporting
import com.droidective.core.AccessLevel
import com.droidective.core.Grants
import com.droidective.platform.RootUse
import com.droidective.platform.ShizukuStatus

/** The access ladder: what the app has at each level, and the one next step for what it lacks. */
@Composable
fun SetupScreen(app: DroidectiveApplication) {
    val access by app.access.access.collectAsStateWithLifecycle()
    val root by app.access.root.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Bumped on every resume, so the special-access and Shizuku rows re-read Android's state.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val canWriteSystem = remember(resumes) { Settings.System.canWrite(context) }
    val hasDnd =
        remember(resumes) {
            context
                .getSystemService(NotificationManager::class.java)
                .isNotificationPolicyAccessGranted
        }
    val shizuku = remember(resumes, access) { app.access.shizukuStatus() }
    var reporting by remember { mutableStateOf(CrashReporting.enabled(context)) }

    val ladder: @Composable ColumnScope.() -> Unit = {
        Rung(AccessLevel.App, "Ordinary app permissions", done = canWriteSystem && hasDnd) {
            Check("Modify system settings", canWriteSystem) {
                context.openSafely(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        "package:${context.packageName}".toUri(),
                    )
                )
            }
            Check("Do Not Disturb access", hasDnd) {
                context.openSafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }

        Rung(AccessLevel.Granted, "WRITE_SECURE_SETTINGS and DUMP", done = access.granted) {
            if (!access.granted) {
                Text(
                    "Connect the phone to a Mac running Droidective and use Phone setup, or run " +
                        "these once from any computer with adb; the last one lets Crash " +
                        "Catcher read crash reports. Setting up Shell below also grants them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val command = grantCommand(context.packageName)
                Code(command)
                OutlinedButton(onClick = { context.copy("adb command", command) }) {
                    Text("Copy command")
                }
            }
        }

        Rung(
            AccessLevel.Shell,
            "The shell user, through Shizuku or root",
            done = access.shell != null,
        ) {
            access.shell?.let { Text("Running through ${it.title}.") }
                ?: ShizukuStep(shizuku, onRequest = app.access::requestShizukuPermission, context)
        }

        Rung(
            AccessLevel.Root,
            "su, through Magisk or KernelSU",
            done = access.shell?.isRoot == true,
        ) {
            SwitchRow(
                "Use root when it’s available",
                checked = root != RootUse.Off,
                onChange = { app.access.rootAllowed = it },
            )
            Text(
                when (root) {
                    RootUse.Off,
                    RootUse.Granted ->
                        "Turning this on asks su for access once. Root also covers everything " +
                            "Shell does."
                    RootUse.Asking -> "Waiting for your root manager to answer…"
                    RootUse.Refused ->
                        "su said no. Allow Droidective in your root manager, then turn this " +
                            "off and on to ask again."
                },
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (root == RootUse.Refused) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val thisApp: @Composable ColumnScope.() -> Unit = {
        UpdatesCard()
        if (CrashReporting.available) {
            CrashReportsCard(reporting) {
                CrashReporting.setEnabled(context, it)
                reporting = it
            }
        }
        OwnCrashesCard(app.ownCrashes, reporting = CrashReporting.available && reporting)
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .windowInsetsPadding(ScaffoldDefaults.contentWindowInsets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Setup", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Each toggle needs one of four levels. Without its level, a toggle opens the closest " +
                "system screen instead, or brings you here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Side by side where there's room: the ladder, and the app's own settings.
        AdaptiveColumns(minWidth = SETUP_COLUMN_MIN) { columns ->
            if (columns > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = ladder,
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = thisApp,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ladder()
                    thisApp()
                }
            }
        }
    }
}

private val SETUP_COLUMN_MIN = 420.dp

@Composable
private fun ShizukuStep(status: ShizukuStatus, onRequest: () -> Unit, context: Context) {
    when (status) {
        ShizukuStatus.NotInstalled -> {
            Text("Install Shizuku, a free app that gives other apps the shell user’s access.")
            Button(
                onClick = { context.openSafely(Intent(Intent.ACTION_VIEW, SHIZUKU_PAGE.toUri())) }
            ) {
                Text("Get Shizuku")
            }
        }
        ShizukuStatus.NotRunning -> {
            Text(
                "Start Shizuku from its own app with wireless debugging, or from your Mac with " +
                    "Droidective’s Phone setup."
            )
            Button(
                onClick = {
                    context.packageManager
                        .getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                        ?.let(context::openSafely)
                }
            ) {
                Text("Open Shizuku")
            }
        }
        ShizukuStatus.Attaching -> Text("Connecting to Shizuku…")
        ShizukuStatus.NeedsPermission -> {
            Text("Shizuku is running. Allow Droidective to use it.")
            Button(onClick = onRequest) { Text("Allow in Shizuku") }
        }
        ShizukuStatus.Ready -> Text("Shizuku is ready.")
    }
}

@Composable
private fun Rung(
    level: AccessLevel,
    title: String,
    done: Boolean,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelChip(level, missing = false)
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (done) {
                    Icon(
                        painterResource(Symbols.byName.getValue("check_circle").filled),
                        contentDescription = "Done",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun Check(label: String, done: Boolean, onAllow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        if (done) Text("Allowed", color = MaterialTheme.colorScheme.primary)
        else OutlinedButton(onClick = onAllow) { Text("Allow") }
    }
}

@Composable
private fun Code(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp),
        )
    }
}

/** The adb command lines a computer runs to give this app the Granted level. */
fun grantCommand(packageName: String): String =
    Grants.commands(packageName, Build.VERSION.SDK_INT).joinToString("\n") {
        "adb shell ${it.line}"
    }

/** Puts [text] on the clipboard; Android 13 and later confirm it themselves. */
fun Context.copy(label: String, text: String) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, text))
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_PAGE = "https://shizuku.rikka.app/download/"
