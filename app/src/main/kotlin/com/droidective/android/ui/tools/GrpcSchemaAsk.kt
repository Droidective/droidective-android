package com.droidective.android.ui.tools

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.droidective.android.proxy.ProtoFolder
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.ServerSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asking a call's server for its schema, from the call's detail: whether that is under way, and
 * what the server said when it said no.
 */
internal class ServerAsk(val asking: Boolean, val refused: String?, val onAsk: () -> Unit)

/**
 * The ask for [call], which keeps what the server gives in the protos folder and then calls
 * [onKept], so the call reads again with it.
 */
@Composable
internal fun rememberServerAsk(call: Flow, say: (String) -> Unit, onKept: () -> Unit): ServerAsk {
    val folder = ProtoFolder(LocalContext.current)
    val scope = rememberCoroutineScope()
    var asking by remember(call.id) { mutableStateOf(false) }
    var refused by remember(call.id) { mutableStateOf<String?>(null) }
    return ServerAsk(asking, refused) {
        asking = true
        refused = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { ServerSchema().ask(call) }
            when (outcome) {
                is ServerSchema.Outcome.Found -> {
                    val kept = withContext(Dispatchers.IO) { folder.keep(call.host, outcome.set) }
                    if (kept) {
                        onKept()
                        say(
                            "Read ${call.host}'s schema: ${outcome.files} files, kept with the rest"
                        )
                    } else {
                        refused = "There is no shared storage on this phone to keep it on."
                    }
                }
                is ServerSchema.Outcome.Refused -> refused = outcome.why
            }
            asking = false
        }
    }
}

/**
 * Under a call's messages when they read without names: ask the server, when it has reflection on,
 * or put the app's own schema on the phone.
 */
@Composable
internal fun SchemaNote(reading: Reading) {
    ToolNote(
        "Fields have names with the app's schema. A server with reflection on gives its own; or " +
            "compile the app's .proto files with protoc --include_imports " +
            "--descriptor_set_out=app.protoset, then adb push app.protoset to " +
            "${reading.folder ?: "the protos folder"}."
    )
    val ask = reading.ask ?: return
    OutlinedButton(enabled = !ask.asking, onClick = ask.onAsk) {
        Text(if (ask.asking) "Asking the server…" else "Ask the server for its schema")
    }
    ask.refused?.let { ToolNote(it) }
}
