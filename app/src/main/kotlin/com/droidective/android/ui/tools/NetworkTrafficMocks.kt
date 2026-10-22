package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.MockDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One mocked answer, open to change: which requests it answers, and with what. Saving applies it at
 * once to a proxy that is running.
 */
@Composable
fun MockEditor(start: Mock, isNew: Boolean, tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = remember(start) { MockDraft.of(start) }
    var url by rememberSaveable { mutableStateOf(draft.url) }
    var method by rememberSaveable { mutableStateOf(draft.method) }
    var status by rememberSaveable { mutableStateOf(draft.status) }
    var headers by rememberSaveable { mutableStateOf(draft.headers) }
    var body by rememberSaveable { mutableStateOf(draft.body) }
    var enabled by rememberSaveable { mutableStateOf(draft.enabled) }
    var problem by remember { mutableStateOf<String?>(null) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace)
    ToolScaffold(if (isNew) "New mock" else "Mock", tool) {
        ToolNote(
            "Requests to this URL are answered here and never reach the server. * stands for " +
                "anything, and the query isn't matched."
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL") },
            singleLine = true,
            textStyle = mono,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = method,
                onValueChange = { method = it },
                label = { Text("Method, or any") },
                singleLine = true,
                textStyle = mono,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = status,
                onValueChange = { status = it },
                label = { Text("Status") },
                singleLine = true,
                textStyle = mono,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = headers,
            onValueChange = { headers = it },
            label = { Text("Headers, one Name: value to a line") },
            textStyle = mono,
            modifier = Modifier.fillMaxWidth().heightIn(min = FIELD_DP.dp),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Body") },
            textStyle = mono,
            modifier = Modifier.fillMaxWidth().heightIn(min = FIELD_DP.dp),
        )
        Row(
            Modifier.fillMaxWidth().clickable { enabled = !enabled }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Answer with this", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    when (
                        val built =
                            MockDraft(url, method, status, headers, body, enabled).build(start.id)
                    ) {
                        is MockDraft.Built.Invalid -> problem = built.reason
                        is MockDraft.Built.Ready -> {
                            scope.launch(Dispatchers.IO) {
                                TrafficProxy.saveMock(context, built.mock)
                            }
                            tool.onBack()
                        }
                    }
                }
            ) {
                Text("Save")
            }
            if (!isNew) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) { TrafficProxy.removeMock(context, start.id) }
                        tool.onBack()
                    }
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

/** A list row: what a mock answers, whether it's on, and a tap to edit it. */
@Composable
internal fun MockRow(mock: Mock, onEdit: () -> Unit, onEnable: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                mock.url,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${mock.method ?: "Any method"} · ${mock.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = mock.enabled, onCheckedChange = onEnable)
    }
}

private const val FIELD_DP = 120
