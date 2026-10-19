package com.droidective.android.ui.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.install.InstallOutcome
import com.droidective.android.install.InstallResultReceiver
import com.droidective.android.install.InstallResults
import com.droidective.android.ui.openSafely
import com.droidective.core.apps.bundles.ApksToc
import com.droidective.core.apps.bundles.AppBundleManifest
import com.droidective.core.apps.bundles.AppPackageFormat
import com.droidective.core.apps.bundles.InstallPlan
import com.droidective.platform.AppInstaller
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file picked to install, read far enough to say what installing it will do. */
private sealed interface Picked {
    val name: String

    data class Refused(override val name: String, val reason: String) : Picked

    /** [plan] is null for a plain APK, which installs as it is. */
    data class Ready(
        override val name: String,
        val uri: Uri,
        val format: AppPackageFormat,
        val plan: InstallPlan?,
    ) : Picked
}

/**
 * The Mac's Install App, on the phone: an APK, or an APKS, XAPK or APKM bundle with only the splits
 * this phone needs, through Android's installer, which asks before it installs.
 */
@Composable
fun InstallAppTool(tool: ToolContext) {
    val context = LocalContext.current
    val installer = remember { AppInstaller(context) }
    val scope = rememberCoroutineScope()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val allowed = remember(resumes) { installer.allowed() }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var reading by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<Int?>(null) }
    var written by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val outcomes by InstallResults.all.collectAsStateWithLifecycle()
    val outcome = session?.let { outcomes[it] }

    val choose =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            reading = true
            session = null
            failure = null
            scope.launch {
                picked = withContext(Dispatchers.IO) { read(context, installer, uri) }
                reading = false
            }
        }

    ToolScaffold("Install App", tool) {
        ToolNote(
            "An APK, or an APKS, XAPK or APKM bundle. A bundle installs only the splits this " +
                "phone needs: its processor, screen density and languages."
        )
        OutlinedButton(onClick = { choose.launch(arrayOf("*/*")) }, enabled = !reading) {
            Text(if (picked == null) "Choose a file" else "Choose another")
        }
        if (reading) ToolNote("Reading the file…")
        when (val now = picked) {
            null -> Unit
            is Picked.Refused -> ToolNote("${now.name}: ${now.reason}")
            is Picked.Ready -> {
                PlanSection(now)
                if (!allowed) {
                    ToolNote(
                        "Android asks before an app installs other apps. Allow Droidective once."
                    )
                    OutlinedButton(
                        onClick = {
                            context.openSafely(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    "package:${context.packageName}".toUri(),
                                )
                            )
                        }
                    ) {
                        Text("Allow installing apps")
                    }
                }
                val busy = written != null && outcome == null && failure == null
                Button(
                    enabled = allowed && now.plan?.problem == null && !busy,
                    onClick = {
                        failure = null
                        written = 0 to 0
                        scope.launch {
                            try {
                                session =
                                    withContext(Dispatchers.IO) {
                                        installer.install(
                                            now.uri,
                                            now.format,
                                            now.plan?.selection?.files.orEmpty(),
                                            Intent(context, InstallResultReceiver::class.java),
                                        ) { done, total ->
                                            written = done to total
                                        }
                                    }
                            } catch (e: IOException) {
                                failure = e.message ?: "The file couldn't be read."
                                written = null
                            }
                        }
                    },
                ) {
                    Text("Install")
                }
                Status(written, outcome, failure)
            }
        }
    }
}

@Composable
private fun PlanSection(picked: Picked.Ready) {
    val plan = picked.plan
    ToolSection(picked.format.displayName) {
        InfoRow("File", picked.name)
        plan?.manifest?.let { manifest ->
            InfoRow("App", manifest.appName ?: manifest.packageName)
            InfoRow("Package", manifest.packageName, mono = true)
            InfoRow("Version", manifest.versionName)
        }
        if (plan != null) {
            InfoRow(
                "Installs",
                "${plan.selection.files.size} of ${plan.candidates.size} APKs" +
                    (plan.selection.chosenAbi?.let { " · $it" } ?: ""),
            )
            if (plan.selection.files.isNotEmpty()) {
                Text(
                    plan.selection.files.joinToString("\n") { it.substringAfterLast('/') },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plan.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (plan.obbFiles.isNotEmpty()) {
                ToolNote(
                    "It also carries ${plan.obbFiles.size} OBB data file(s), which this install " +
                        "doesn't place. A game that needs them may download them itself."
                )
            }
        }
    }
}

@Composable
private fun Status(written: Pair<Int, Int>?, outcome: InstallOutcome?, failure: String?) {
    val text =
        when {
            failure != null -> failure
            outcome is InstallOutcome.Installed ->
                "Installed${outcome.packageName?.let { " · $it" } ?: ""}."
            outcome is InstallOutcome.Failed -> outcome.message
            outcome is InstallOutcome.Confirming -> "Confirm the install in Android's dialog."
            written != null && written.second > 0 ->
                "Writing ${written.first} of ${written.second}…"
            written != null -> "Starting…"
            else -> return
        }
    Text(
        text,
        color =
            if (failure != null || outcome is InstallOutcome.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

/** Names the file, refuses what isn't installable, and plans a bundle's splits. */
private fun read(context: Context, installer: AppInstaller, uri: Uri): Picked {
    val name = displayName(context, uri) ?: uri.lastPathSegment ?: "The file"
    val format =
        AppPackageFormat.detect(name)
            ?: return Picked.Refused(name, "isn't an APK, APKS, XAPK or APKM.")
    if (!format.isBundle) return Picked.Ready(name, uri, format, plan = null)
    return try {
        val entries = installer.entries(uri)
        val manifest =
            AppBundleManifest.fileName(format)
                ?.let { installer.readBytes(uri, it) }
                ?.let { AppBundleManifest.parse(it.decodeToString(), format) }
        // bundletool's table of contents says which files belong to which Android versions.
        val toc =
            if (format == AppPackageFormat.Apks && TOC in entries) {
                installer.readBytes(uri, TOC)?.let(ApksToc::parse)
            } else {
                null
            }
        Picked.Ready(
            name,
            uri,
            format,
            InstallPlan.of(
                format,
                entries,
                manifest,
                AppInstaller.thisPhone(context),
                Build.VERSION.SDK_INT,
                toc,
            ),
        )
    } catch (e: IOException) {
        Picked.Refused(name, "couldn't be read as a ${format.displayName}: ${e.message}")
    }
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

private const val TOC = "toc.pb"
