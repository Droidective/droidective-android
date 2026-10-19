package com.droidective.android.ui.tools

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.bugreport.BugReportRun
import com.droidective.android.bugreport.BugReports
import com.droidective.android.droidective
import com.droidective.android.ui.openSafely

/**
 * The Mac's Bug Report: the full zipped report Android Studio and the Play Console ask for, taken
 * as the shell user and saved to Downloads to share. It goes on if this screen is left.
 */
@Composable
fun BugReportTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val run by BugReports.state.collectAsStateWithLifecycle()

    ToolScaffold("Bug Report", tool) {
        if (access.shell == null) {
            ToolNote("bugreportz runs as the shell user, through Shizuku or root.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        ToolNote(
            "Everything Android keeps about its state: logs, every service's dump, the last " +
                "crashes and ANRs. It holds personal data too, such as accounts and network " +
                "names, so share it with people you trust."
        )
        when (val now = run) {
            is BugReportRun.Gathering -> {
                if (now.done != null && now.of != null) {
                    LinearProgressIndicator(
                        progress = { now.done / now.of.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ToolNote("Taking it now. It takes a minute or two; you can leave this screen.")
            }
            is BugReportRun.Saved -> {
                ToolSection("Saved") {
                    InfoRow("File", "Download/${now.name}", copyable = true, mono = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BugReports.share(now)?.let { share ->
                            Button(onClick = { context.openSafely(share) }) { Text("Share") }
                        }
                        OutlinedButton(onClick = { context.openSafely(downloads()) }) {
                            Text("Open Downloads")
                        }
                    }
                }
                TakeOne(again = true) { BugReports.start(context) }
            }
            is BugReportRun.Failed -> {
                ToolNote(now.message)
                TakeOne(again = true) { BugReports.start(context) }
            }
            null -> TakeOne(again = false) { BugReports.start(context) }
        }
    }
}

@Composable
private fun TakeOne(again: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) { Text(if (again) "Take another" else "Take a bug report") }
}

private fun downloads(): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(
            "content://com.android.externalstorage.documents/document/primary%3ADownload".toUri(),
            DocumentsContract.Document.MIME_TYPE_DIR,
        )
