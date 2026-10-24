package com.droidective.android.ui.tools

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.copy
import com.droidective.core.apps.ApkContents
import com.droidective.core.apps.ApkFile
import com.droidective.core.apps.ApkNode
import com.droidective.core.apps.ZipRecord
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What takes the room in an APK: its folders and files, biggest first, each with what it takes in
 * the APK (so in a download) and what it holds unpacked. An installed app picks which of its APKs.
 */
@Composable
fun ApkFilesSection(files: List<ApkFile>) {
    if (files.isEmpty()) return
    val context = LocalContext.current
    var chosen by remember(files) { mutableStateOf(files.first()) }
    var folder by remember(chosen) { mutableStateOf("") }
    var entries by remember(chosen) { mutableStateOf<List<ZipRecord>?>(null) }
    var failure by remember(chosen) { mutableStateOf<String?>(null) }
    LaunchedEffect(chosen) {
        try {
            entries = withContext(Dispatchers.IO) { ApkContents.read(File(chosen.path)) }
        } catch (e: IOException) {
            failure = e.message ?: "It couldn't be read."
        }
    }
    ToolSection("Files") {
        if (files.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { file ->
                    FilterChip(
                        selected = file == chosen,
                        onClick = { chosen = file },
                        label = { Text(file.name) },
                    )
                }
            }
        }
        val all = entries
        when {
            failure != null -> ToolNote(failure.orEmpty())
            all == null -> ToolNote("Reading…")
            else -> {
                val total = remember(all) { all.sumOf { it.packed } }
                val nodes = remember(all, folder) { ApkContents.list(all, folder) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ApkContents.crumbs(if (files.size > 1) chosen.name else "APK", folder).forEach {
                        AssistChip(onClick = { folder = it.path }, label = { Text(it.label) })
                    }
                }
                Shortened(nodes) { node ->
                    NodeRow(node, total) {
                        if (node.folder) folder = node.path else context.copy("Path", node.path)
                    }
                }
                ToolNote(
                    "${files(all.size)}. Sizes are what each takes in the APK. Tap a folder to " +
                        "open it, a file to copy its path."
                )
            }
        }
    }
}

@Composable
private fun NodeRow(node: ApkNode, total: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val icon = if (node.folder) "folder" else "receipt_long"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(Symbols.byName.getValue(icon).outlined), null, Modifier.size(20.dp))
        Column(
            Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    node.name,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(size(node.packed))
            }
            LinearProgressIndicator(
                progress = { if (total > 0) node.packed / total.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                listOf(
                        when {
                            node.folder -> "${files(node.files)}, ${size(node.unpacked)} unpacked"
                            node.stored -> "Stored uncompressed"
                            else -> "Compressed from ${size(node.unpacked)}"
                        },
                        ApkContents.share(node.packed, total),
                    )
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun files(count: Int) = if (count == 1) "1 file" else "$count files"
