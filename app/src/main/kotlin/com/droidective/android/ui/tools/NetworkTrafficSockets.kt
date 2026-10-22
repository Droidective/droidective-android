package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.copy
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import com.droidective.core.net.proxy.BodyShape
import com.droidective.core.net.proxy.BodyView
import com.droidective.core.net.proxy.FlowLabels
import com.droidective.core.net.ws.SocketMessage
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * What a WebSocket said, oldest first, as it says it. The list catches up at the pace the flows do,
 * and shows the last [SHOWN] messages: a socket can talk faster than a phone lays out rows.
 */
@Composable
internal fun SocketMessages(flowId: Long) {
    var messages by remember(flowId) { mutableStateOf(TrafficProxy.messages.of(flowId)) }
    var unread by remember(flowId) { mutableStateOf(TrafficProxy.messages.unreadOf(flowId)) }
    LaunchedEffect(flowId) {
        TrafficProxy.changes.collect {
            messages = TrafficProxy.messages.of(flowId)
            unread = TrafficProxy.messages.unreadOf(flowId)
            delay(PACE_MILLIS)
        }
    }
    ToolSection("Messages") {
        // Said first: a gap in what's shown, with nothing to say why, reads as a quiet socket.
        unread[true]?.let {
            ToolNote("What the app sends isn't read any more: $it. It still goes.")
        }
        unread[false]?.let {
            ToolNote("What the server sends isn't read any more: $it. It still arrives.")
        }
        if (messages.isEmpty()) {
            if (unread.isNotEmpty()) return@ToolSection
            ToolNote("Nothing yet. What either side sends shows here as it crosses.")
            return@ToolSection
        }
        val fromApp = messages.count { it.fromApp }
        ToolNote(
            "${messages.size} kept: $fromApp from the app, ${messages.size - fromApp} from the " +
                "server. ↑ went to the server, ↓ came back."
        )
        if (messages.size > SHOWN) ToolNote("The last $SHOWN are shown.")
        messages.takeLast(SHOWN).forEach { message ->
            HorizontalDivider()
            SocketRow(message)
        }
    }
}

@Composable
private fun SocketRow(message: SocketMessage) {
    val context = LocalContext.current
    var open by rememberSaveable(message.at, message.length) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (message.fromApp) "↑" else "↓",
                fontWeight = FontWeight.Bold,
                color =
                    if (message.fromApp) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary,
            )
            Text(
                TIME.format(Instant.ofEpochMilli(message.at)),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                describe(message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val shown = remember(message, open) { shown(message, open) }
        if (shown.isNotEmpty()) {
            Text(
                shown,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (open) Int.MAX_VALUE else CLOSED_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (open && message.truncated) {
            ToolNote(
                "Only the first ${FlowLabels.size(message.data.size.toLong())} of " +
                    "${FlowLabels.size(message.length)} was kept."
            )
        }
        if (open && message.kind == SocketMessage.Kind.Text) {
            TextButton(onClick = { context.copy("The message", message.text) }) { Text("Copy") }
        }
    }
}

/** What kind of message, and how big, when that says more than its text does. */
private fun describe(message: SocketMessage): String {
    val size = FlowLabels.size(message.length)
    return when (message.kind) {
        SocketMessage.Kind.Text -> size
        SocketMessage.Kind.Binary -> "Binary, $size"
        SocketMessage.Kind.Close -> "Closed" + (message.closeCode?.let { " · $it" } ?: "")
        SocketMessage.Kind.Ping -> "Ping"
        SocketMessage.Kind.Pong -> "Pong"
    }
}

/** A message's words: its text, laid out as JSON when open and it is JSON, or a close's reason. */
private fun shown(message: SocketMessage, open: Boolean): String =
    when (message.kind) {
        SocketMessage.Kind.Text -> if (open) laidOut(message) else message.text
        SocketMessage.Kind.Close -> message.data.drop(2).toByteArray().decodeToString()
        SocketMessage.Kind.Ping,
        SocketMessage.Kind.Pong -> message.text
        SocketMessage.Kind.Binary -> ""
    }

private fun laidOut(message: SocketMessage): String {
    val body = CapturedBody(message.data, message.length, message.truncated)
    val shape = BodyView.of(Headers().add(Headers.CONTENT_TYPE, JSON), body)
    return (shape as? BodyShape.Json)?.pretty ?: message.text
}

private const val SHOWN = 200
private const val CLOSED_LINES = 3
private const val PACE_MILLIS = 250L
private const val JSON = "application/json"
