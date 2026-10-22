package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droidective.android.proxy.TrafficProxy
import com.droidective.core.net.proxy.Remap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One remap, open to change: which requests it takes, and the server they go to instead. Saving
 * applies it at once to a proxy that is running.
 */
@Composable
fun RemapEditor(start: Remap, isNew: Boolean, tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by rememberSaveable { mutableStateOf(start.from) }
    var to by rememberSaveable { mutableStateOf(start.to) }
    var enabled by rememberSaveable { mutableStateOf(start.enabled) }
    var problem by remember { mutableStateOf<String?>(null) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace)
    ToolScaffold(if (isNew) "New remap" else "Remap", tool) {
        ToolNote(
            "Requests to this URL go to another server instead, their path and query as the app " +
                "asked. * stands for anything, and the query isn't matched. Charles calls this " +
                "Map Remote."
        )
        OutlinedTextField(
            value = from,
            onValueChange = { from = it },
            label = { Text("Requests to") },
            singleLine = true,
            textStyle = mono,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = to,
            onValueChange = { to = it },
            label = { Text("Send them to") },
            placeholder = { Text("https://staging.example.com", style = mono) },
            singleLine = true,
            textStyle = mono,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        ToolNote(
            "A server on your computer: http://10.0.2.2:<port> from the emulator, or " +
                "http://127.0.0.1:<port> on a phone after adb reverse tcp:<port> tcp:<port>."
        )
        Row(
            Modifier.fillMaxWidth().clickable { enabled = !enabled }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("On", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val remap = start.copy(from = from.trim(), to = to.trim(), enabled = enabled)
                    problem = remap.problem()
                    if (problem == null) {
                        scope.launch(Dispatchers.IO) { TrafficProxy.saveRemap(context, remap) }
                        tool.onBack()
                    }
                }
            ) {
                Text("Save")
            }
            if (!isNew) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) { TrafficProxy.removeRemap(context, start.id) }
                        tool.onBack()
                    }
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

/** A list row: which requests a remap takes and where to, whether it's on, a tap to edit it. */
@Composable
internal fun RemapRow(remap: Remap, onEdit: () -> Unit, onEnable: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                remap.from,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "to ${remap.to}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = remap.enabled, onCheckedChange = onEnable)
    }
}
