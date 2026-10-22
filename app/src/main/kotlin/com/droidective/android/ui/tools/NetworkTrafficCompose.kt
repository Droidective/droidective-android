package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidective.android.proxy.TrafficProxy
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.RequestDraft
import kotlinx.coroutines.launch

/**
 * A captured request with everything open to change before it goes again: the method, the URL, the
 * headers one to a line, the body. What it sends is recorded as a repeat of [flow].
 *
 * @param onSent opens the flow the send made.
 */
@Composable
fun RequestEditor(flow: Flow, start: RequestDraft, tool: ToolContext, onSent: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var method by rememberSaveable { mutableStateOf(start.method) }
    var url by rememberSaveable { mutableStateOf(start.url) }
    var headers by rememberSaveable { mutableStateOf(start.headers) }
    var body by rememberSaveable { mutableStateOf(start.body) }
    var problem by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace)
    ToolScaffold("Edit and send", tool) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = method,
                onValueChange = { method = it },
                label = { Text("Method") },
                singleLine = true,
                textStyle = mono,
                modifier = Modifier.weight(METHOD_WEIGHT),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                singleLine = true,
                textStyle = mono,
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
        ToolNote("Host and Content-Length are worked out from the URL and the body.")
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Body") },
            textStyle = mono,
            modifier = Modifier.fillMaxWidth().heightIn(min = FIELD_DP.dp),
        )
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !sending,
            onClick = {
                when (val built = RequestDraft(method, url, headers, body).build()) {
                    is RequestDraft.Built.Invalid -> problem = built.reason
                    is RequestDraft.Built.Ready -> {
                        problem = null
                        sending = true
                        scope.launch {
                            val sent = TrafficProxy.send(context, built.outgoing, of = flow.id)
                            sending = false
                            onSent(sent.id)
                        }
                    }
                }
            },
        ) {
            Text(if (sending) "Sending…" else "Send")
        }
    }
}

private const val METHOD_WEIGHT = 0.35f
private const val FIELD_DP = 120
