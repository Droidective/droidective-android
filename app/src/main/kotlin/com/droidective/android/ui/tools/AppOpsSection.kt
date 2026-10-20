package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidective.core.apps.AppOp
import com.droidective.core.apps.AppOps

/**
 * An app's app-ops: the special accesses Android tracks beyond runtime permissions, such as running
 * in the background or drawing over other apps, each set in a tap.
 */
@Composable
fun AppOpsSection(packageName: String, tool: ToolContext) {
    val context = LocalContext.current
    val shell = rememberToolShell(tool)
    var reads by remember { mutableIntStateOf(0) }
    var ops by remember(packageName) { mutableStateOf<List<AppOp>?>(null) }
    var all by remember(packageName) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppOp?>(null) }
    LaunchedEffect(packageName, reads) {
        ops = shellOutput(context, AppOps.get(packageName))?.let(AppOps::parse)
    }

    ToolSection("App ops") {
        val now = ops
        if (now == null) {
            ToolNote("Reading…")
            return@ToolSection
        }
        val byName = now.associateBy { it.name }
        AppOps.known.forEach { known ->
            val op = byName[known.name] ?: AppOp(known.name, "default", null, null)
            OpRow(known.title, op, known.meaning) { editing = op }
        }
        if (all) {
            now.filter { op -> AppOps.known.none { it.name == op.name } }
                .forEach { op -> OpRow(op.name, op, null) { editing = op } }
        } else {
            TextButton(onClick = { all = true }) { Text("Show all ${now.size}") }
        }
        TextButton(
            onClick = {
                shell.run("Reset", listOf(AppOps.reset(packageName)), "App ops reset") { reads++ }
            }
        ) {
            Text("Reset all to Android's defaults")
        }
    }

    editing?.let { op ->
        ModeDialog(op, onDismiss = { editing = null }) { mode ->
            editing = null
            shell.run(
                "Set ${op.name}",
                listOf(AppOps.set(packageName, op.name, mode)),
                "${op.name}: $mode",
            ) {
                reads++
            }
        }
    }
}

@Composable
private fun OpRow(title: String, op: AppOp, meaning: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = if (meaning == null) FontFamily.Monospace else null)
            meaning?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listOfNotNull(
                    op.lastUsed?.let { "used $it ago" },
                    op.lastRefused?.let { "refused $it ago" },
                )
                .takeIf { it.isNotEmpty() }
                ?.let {
                    Text(
                        it.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
        Text(op.mode, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ModeDialog(op: AppOp, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(op.name) },
        text = {
            Column {
                AppOps.modes.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(mode) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = op.mode == mode, onClick = { onPick(mode) })
                        Text(mode, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
