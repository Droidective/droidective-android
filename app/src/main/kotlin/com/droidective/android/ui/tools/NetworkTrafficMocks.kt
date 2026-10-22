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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.droidective.android.proxy.MockFolder
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.copy
import com.droidective.core.net.proxy.FlowLabels
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.MockDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var file by rememberSaveable { mutableStateOf(draft.file) }
    var fromFile by rememberSaveable { mutableStateOf(draft.file.isNotEmpty()) }
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
        AnswerFrom(fromFile) { fromFile = it }
        if (fromFile) {
            FileField(file) { file = it }
        } else {
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Body") },
                textStyle = mono,
                modifier = Modifier.fillMaxWidth().heightIn(min = FIELD_DP.dp),
            )
        }
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
                    if (fromFile && file.isBlank()) {
                        problem = "Name the file in the mocks folder it answers with."
                        return@Button
                    }
                    val named = if (fromFile) file else ""
                    when (
                        val built =
                            MockDraft(url, method, status, headers, body, enabled, named)
                                .build(start.id)
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

/** Whether the mock answers with the text typed here, or with a file in the mocks folder. */
@Composable
private fun AnswerFrom(fromFile: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("This text", "A file").forEachIndexed { index, label ->
            SegmentedButton(
                selected = fromFile == (index == 1),
                onClick = { onChange(index == 1) },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
            ) {
                Text(label)
            }
        }
    }
}

/**
 * A file in the mocks folder, with where that folder is, how to put a fixture there, and whether
 * this one is there yet. It is read for every answer, so pushing it again changes the answer.
 */
@Composable
private fun FileField(file: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val mocks = remember { MockFolder(context) }
    val folder = remember { mocks.folder()?.path }
    val size by
        produceState<Long?>(null, file) {
            value = withContext(Dispatchers.IO) { mocks.find(file.trim())?.length() }
        }
    OutlinedTextField(
        value = file,
        onValueChange = onChange,
        label = { Text("File in the mocks folder") },
        placeholder = { Text("menu.json") },
        singleLine = true,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        supportingText = {
            Text(
                when {
                    file.isBlank() -> "Read again for every answer, so a new push changes it."
                    size != null -> "There: ${FlowLabels.size(size ?: 0)}."
                    else -> "Not there yet."
                }
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (folder == null) {
        ToolNote("This phone has no shared storage for the mocks folder.")
        return
    }
    val push = "adb push ${file.trim().ifEmpty { "menu.json" }} $folder/"
    ToolNote("The folder is $folder. From your computer:")
    Text(push, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { context.copy("adb push", push) }) { Text("Copy") }
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
                listOfNotNull(mock.method ?: "Any method", "${mock.status}", mock.file)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = mock.enabled, onCheckedChange = onEnable)
    }
}

private const val FIELD_DP = 120
