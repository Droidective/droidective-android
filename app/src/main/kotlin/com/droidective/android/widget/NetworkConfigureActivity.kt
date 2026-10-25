package com.droidective.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.droidective.android.droidective
import com.droidective.android.ui.DroidectiveTheme
import com.droidective.android.ui.SwitchRow
import com.droidective.android.ui.Symbols
import com.droidective.core.network.Copies
import com.droidective.core.network.NetworkCard
import com.droidective.core.network.NetworkCards
import com.droidective.core.network.NetworkNow
import com.droidective.core.network.PublicIp
import com.droidective.core.network.Transport
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * A network widget's settings: what it shows, with a preview of it drawing this phone's network,
 * and what a tap copies. Opens when one is placed, from its long-press menu, and from Widgets.
 */
class NetworkConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId =
            intent
                ?.extras
                ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Backing out still keeps the widget, showing everything.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        NetworkWidgets.watch(this)
        val start = NetworkWidgets.card(this, widgetId)
        setContent {
            DroidectiveTheme {
                Surface(Modifier.fillMaxSize()) {
                    NetworkSettings(start) { card ->
                        NetworkWidgets.save(this, widgetId, card)
                        lifecycleScope.launch {
                            NetworkWidgets.refresh(this@NetworkConfigureActivity)
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkSettings(start: NetworkCard, onDone: (NetworkCard) -> Unit) {
    val context = LocalContext.current
    var card by rememberSaveable(stateSaver = CardSaver) { mutableStateOf(start) }
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val now by
        produceState<NetworkNow?>(null, card.showPublic) {
            value = NetworkWidgets.read(context, card)
        }
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Network widget",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onDone(card) }) { Text("Done") }
        }
        Preview(now, card)
        Section("Show")
        SwitchRow(
            "Network name",
            card.showName,
            note =
                if (access.shell != null) "The Wi-Fi name, or Mobile data"
                else
                    "Android tells apps the Wi-Fi name only with Shell or location access, so " +
                        "without Shell it says Wi-Fi.",
        ) {
            card = card.copy(showName = it)
        }
        SwitchRow("Local IP", card.showLocal, note = "This phone's address on the network") {
            card = card.copy(showLocal = it)
        }
        SwitchRow(
            "Public IP",
            card.showPublic,
            note =
                "The address servers see, looked up from ${PublicIp.URL.substringAfter("://")} " +
                    "when the network changes",
        ) {
            card = card.copy(showPublic = it)
        }
        Section("A tap copies")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Copies.entries.forEachIndexed { index, copies ->
                SegmentedButton(
                    selected = card.copies == copies,
                    onClick = { card = card.copy(copies = copies) },
                    shape = SegmentedButtonDefaults.itemShape(index, Copies.entries.size),
                ) {
                    Text(copies.title)
                }
            }
        }
    }
}

/** The widget as it will draw, with this phone's network, on the colours of the app's theme. */
@Composable
private fun Preview(now: NetworkNow?, card: NetworkCard) {
    val shown = now ?: NetworkNow(Transport.Other)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = PREVIEW_MAX).fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (card.showName) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(
                            Symbols.byName.getValue(symbolOf(shown.transport)).outlined
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (now == null) "Reading…" else NetworkCards.title(shown),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            NetworkCards.lines(shown, card).forEach { line ->
                if (line.main) {
                    Text(
                        line.value,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        line.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        line.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** The choices as JSON, so a turned screen keeps the ones not yet saved. */
private val CardSaver =
    Saver<NetworkCard, String>(
        save = { Json.encodeToString(NetworkCard.serializer(), it) },
        restore = { Json.decodeFromString(NetworkCard.serializer(), it) },
    )

private val PREVIEW_MAX = 360.dp
