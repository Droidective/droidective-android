package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.reactotron.Reactotron
import com.droidective.core.reactotron.ReactotronEvent
import com.droidective.core.reactotron.ReactotronTimeline

/**
 * Reactotron's REPL: the values an app shares with `Reactotron.repl(name, value)`, and code run in
 * the app with them as `this`. What it comes to shows here, and in the timeline as a state read's
 * does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReplSection(entries: List<ReactotronTimeline.Entry>, onOpen: (Long) -> Unit) {
    val shared by Reactotron.replNames.collectAsStateWithLifecycle()
    val clients by Reactotron.clients.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var ran by rememberSaveable { mutableStateOf<Long?>(null) }
    val names = shared.filterKeys { it in clients }.values.flatten().distinct()
    ToolSection("REPL") {
        OutlinedButton(onClick = Reactotron::listRepl) { Text("List what the app shares") }
        if (names.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEach { name ->
                    AssistChip(onClick = { code += "this.$name" }, label = { Text(name) })
                }
            }
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Code, such as this.store.getState()") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = code.isNotBlank(),
                onClick = {
                    ran = System.currentTimeMillis()
                    Reactotron.runRepl(code)
                },
            ) {
                Text("Run in the app")
            }
            if (code.isNotEmpty()) OutlinedButton(onClick = { code = "" }) { Text("Clear") }
        }
        ToolNote(
            "It runs in the app, which needs Reactotron.repl(name, value) for anything to share. " +
                "What it comes to shows here and in the timeline as REPL."
        )
        Answers(entries, ran, THREW, onOpen) { it is ReactotronEvent.ReplResult }
    }
}

private const val THREW = "Nothing came back: code that throws brings nothing back."
