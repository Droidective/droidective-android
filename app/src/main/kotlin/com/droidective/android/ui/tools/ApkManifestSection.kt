package com.droidective.android.ui.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.droidective.android.ui.copy
import com.droidective.core.apps.ApkReport
import com.droidective.platform.ApkReports
import java.io.IOException
import kotlinx.coroutines.launch

/** The APK's AndroidManifest.xml turned back into XML, read when asked for. */
@Composable
fun ApkManifestSection(report: ApkReport) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manifest by remember(report) { mutableStateOf<String?>(null) }
    var status by remember(report) { mutableStateOf<String?>(null) }
    ToolSection("Manifest") {
        val shown = manifest
        if (shown == null) {
            OutlinedButton(
                onClick = {
                    status = "Reading…"
                    scope.launch {
                        try {
                            manifest = ApkReports.manifest(context, report)
                            status = null
                        } catch (e: IOException) {
                            status = e.message ?: "It couldn't be read."
                        }
                    }
                }
            ) {
                Text("Show AndroidManifest.xml")
            }
            status?.let { ToolNote(it) }
            return@ToolSection
        }
        Row {
            TextButton(
                onClick = { context.copy("AndroidManifest.xml", shown) },
                enabled = shown.length <= COPY_LIMIT,
            ) {
                Text("Copy")
            }
            TextButton(onClick = { manifest = null }) { Text("Hide") }
        }
        Text(
            shown,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
        ToolNote(
            "Turned back into XML from the compiled manifest. Numbers Android names are named, " +
                "and a reference shows its resource's name where the app's resources say it." +
                if (shown.length > COPY_LIMIT) " It's too long to copy." else ""
        )
    }
}

/** Past this many characters the clipboard's binder transaction could fail. */
private const val COPY_LIMIT = 200_000
