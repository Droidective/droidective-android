package com.droidective.android.ui.tools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidective.android.ui.automations.AppChooser
import com.droidective.android.ui.copy
import com.droidective.core.apps.ApkComponent
import com.droidective.core.apps.ApkReport
import com.droidective.core.apps.MemInfo
import com.droidective.core.apps.SigningCertificate
import com.droidective.platform.ApkReports
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What an app or an APK file is made of: how it's signed, what it exports, what it asks for, and
 * the native code it ships. Installed apps and picked files alike, with no shell needed.
 */
@Composable
fun ApkInspectorTool(tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ApkReport?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            packageName = null
            report = null
            status = "Reading the file…"
            scope.launch {
                val read = readArchive(context, uri)
                report = read
                status = if (read == null) NOT_AN_APK else null
            }
        }
    LaunchedEffect(packageName) {
        val pkg = packageName ?: return@LaunchedEffect
        status = "Reading…"
        report = ApkReports.installed(context, pkg)
        status = if (report == null) "It isn't installed any more." else null
    }

    ToolScaffold("APK Inspector", tool) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choosing = true }) { Text("An installed app") }
            OutlinedButton(
                onClick = { pick.launch(arrayOf(APK_TYPE, "application/octet-stream")) }
            ) {
                Text("An APK file")
            }
        }
        status?.let { ToolNote(it) }
        report?.let { Report(it) }
    }

    if (choosing) {
        AppChooser("Which app", onDismiss = { choosing = false }) {
            packageName = it.packageName
            choosing = false
        }
    }
}

@Composable
private fun Report(report: ApkReport) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(report.label, style = MaterialTheme.typography.titleLarge)
        Text(
            report.packageName,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TextButton(onClick = { context.copy("APK report", report.text()) }) { Text("Copy report") }
    ToolSection("Overview") {
        InfoRow("Version", "${report.versionName ?: "—"} (${report.versionCode})", copyable = true)
        InfoRow("Min SDK", "${report.minSdk}")
        InfoRow("Target SDK", "${report.targetSdk}")
        report.compileSdk?.let { InfoRow("Compiled against", "$it") }
        report.anatomy?.let { InfoRow("Size", MemInfo.size(it.bytes / BYTES_PER_KB)) }
        if (report.files.size > 1) {
            report.files.forEach { InfoRow(it.name, MemInfo.size(it.bytes / BYTES_PER_KB)) }
        }
    }
    Flags(report)
    Signing(report)
    report.anatomy?.let { anatomy ->
        ToolSection("Code") {
            InfoRow(
                "Native code",
                anatomy.abis
                    .ifEmpty { listOf("None, so it runs on any processor") }
                    .joinToString(", "),
            )
            InfoRow("Dex files", "${anatomy.dexFiles}")
        }
    }
    ApkFilesSection(report.files)
    Permissions(report)
    Components(report.components)
    if (report.features.isNotEmpty()) {
        ToolSection("Hardware it needs") { Shortened(report.features) { Text(it) } }
    }
    if (report.metadata.isNotEmpty()) {
        ToolSection("Metadata") {
            Shortened(report.metadata) { (key, value) ->
                InfoRow(key, value, copyable = true, mono = true)
            }
        }
    }
}

@Composable
private fun Flags(report: ApkReport) {
    val flags = report.flags
    ToolSection("Flags") {
        listOf(
                "Debuggable" to flags.debuggable,
                "Test only" to flags.testOnly,
                "Backup allowed" to flags.allowBackup,
                "Cleartext traffic" to flags.cleartextTraffic,
                "Profileable from the shell" to flags.profileableByShell,
                "Large heap" to flags.largeHeap,
                "Extracts native libraries" to flags.extractNativeLibs,
            )
            .forEach { (label, on) -> InfoRow(label, if (on) "Yes" else "No") }
        if (flags.debuggable && !report.installed) {
            ToolNote("A debuggable build: Play and most stores refuse one.")
        }
    }
}

@Composable
private fun Signing(report: ApkReport) {
    ToolSection("Signing") {
        report.anatomy?.let { anatomy ->
            InfoRow(
                "Schemes",
                anatomy.schemes.ifEmpty { null }?.joinToString(", ") { ApkReport.scheme(it) }
                    ?: "Unsigned",
            )
        }
        if (report.certificates.isEmpty()) ToolNote("Android returned no certificate.")
        report.certificates.forEach { Certificate(it) }
        if (report.rotatedKey) {
            ToolNote("Its signing key was rotated; the certificate above signs it now.")
        }
    }
}

@Composable
private fun Certificate(cert: SigningCertificate) {
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    InfoRow("Subject", cert.subject, copyable = true)
    InfoRow("SHA-256", cert.sha256, copyable = true, mono = true)
    InfoRow("SHA-1", cert.sha1, copyable = true, mono = true)
    InfoRow("Valid until", dates.format(Date(cert.notAfter)))
    if (cert.isDebug) {
        Text(
            "Signed with Android Studio's debug key: no store accepts it.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun Permissions(report: ApkReport) {
    if (report.permissions.isEmpty() && report.definedPermissions.isEmpty()) return
    ToolSection("Permissions it asks for (${report.permissions.size})") {
        Shortened(report.permissions) { permission ->
            InfoRow(
                permission.name.substringAfterLast('.'),
                listOfNotNull(
                        permission.protection ?: "not defined here",
                        when (permission.granted) {
                            true -> "granted"
                            false -> "not granted"
                            null -> null
                        },
                    )
                    .joinToString(" · "),
            )
        }
        if (report.definedPermissions.isNotEmpty()) {
            ToolNote("It defines ${report.definedPermissions.joinToString()}.")
        }
    }
}

@Composable
private fun Components(components: List<ApkComponent>) {
    if (components.isEmpty()) return
    var exportedOnly by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    ToolSection("Components") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = exportedOnly,
                onClick = { exportedOnly = !exportedOnly },
                label = { Text("Exported only") },
            )
        }
        ApkComponent.Kind.entries.forEach { kind ->
            val ofKind = components.filter { it.kind == kind }
            if (ofKind.isEmpty()) return@forEach
            val shown = if (exportedOnly) ofKind.filter { it.exported } else ofKind
            Text(
                "${kind.title}: ${ofKind.size}, ${ofKind.count { it.exported }} exported",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
            Shortened(shown) { component ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { context.copy("Component", component.name) }
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text(
                        component.name.substringAfterLast('.'),
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        describe(component),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (component.open) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ToolNote("Open means any app on the phone can start or bind it. Tap one to copy its name.")
    }
}

/** The first few of [items], and the rest behind a button. */
@Composable
fun <T> Shortened(items: List<T>, row: @Composable (T) -> Unit) {
    var all by remember(items) { mutableStateOf(items.size <= SHORT) }
    (if (all) items else items.take(SHORT)).forEach { row(it) }
    if (!all) TextButton(onClick = { all = true }) { Text("Show all ${items.size}") }
}

private fun describe(component: ApkComponent): String =
    listOfNotNull(
            when {
                component.open -> "open"
                component.exported -> "exported, needs ${component.permission}"
                else -> "private"
            },
            "disabled".takeUnless { component.enabled },
        )
        .joinToString(" · ")

/** Copies the picked file into the cache, where PackageManager can parse it, and reads it. */
private suspend fun readArchive(context: Context, uri: Uri): ApkReport? =
    withContext(Dispatchers.IO) {
        val copy = File(context.cacheDir, "inspect.apk")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copy.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
        } catch (_: IOException) {
            return@withContext null
        }
        ApkReports.archive(context, copy)
    }

private const val APK_TYPE = "application/vnd.android.package-archive"
private const val NOT_AN_APK =
    "Android can't read that as an APK. A bundle (APKS, XAPK, APKM) holds several; open its " +
        "base APK."
private const val SHORT = 8
private const val BYTES_PER_KB = 1024L
