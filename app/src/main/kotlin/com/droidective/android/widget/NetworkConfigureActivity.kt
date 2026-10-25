package com.droidective.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.droidective.android.droidective
import com.droidective.android.ui.DroidectiveTheme
import com.droidective.android.ui.SwitchRow
import com.droidective.android.ui.Symbols
import com.droidective.core.layout.LayoutKind
import com.droidective.core.layout.Style
import com.droidective.core.layout.StylePreset
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
                    // Every change is saved and drawn at once, so there's nothing to lose by
                    // leaving without Done.
                    NetworkSettings(
                        start,
                        onChange = { card ->
                            NetworkWidgets.save(this, widgetId, card)
                            lifecycleScope.launch {
                                NetworkWidgets.refresh(this@NetworkConfigureActivity)
                            }
                        },
                        onDone = ::finish,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkSettings(
    start: NetworkCard,
    onChange: (NetworkCard) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var card by rememberSaveable(stateSaver = CardSaver) { mutableStateOf(start) }
    val change = { next: NetworkCard ->
        card = next
        onChange(next)
    }
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
            Button(onClick = onDone) { Text("Done") }
        }
        Preview(now, card)
        Section("Show")
        val nameProblem = now?.nameProblem
        SwitchRow(
            "Network name",
            card.showName,
            note =
                if (nameProblem != null) "Couldn't read the Wi-Fi name: $nameProblem"
                else "The Wi-Fi name, or Mobile data",
            noteColor =
                if (nameProblem != null) MaterialTheme.colorScheme.error else Color.Unspecified,
        ) {
            change(card.copy(showName = it))
        }
        SwitchRow("Local IP", card.showLocal, note = "This phone's address on the network") {
            change(card.copy(showLocal = it))
        }
        val publicProblem = now?.publicProblem?.takeIf { card.showPublic }
        SwitchRow(
            "Public IP",
            card.showPublic,
            note =
                publicProblem
                    ?: ("The address servers see, looked up from " +
                        "${PublicIp.URL.substringAfter("://")} when the network changes"),
            noteColor =
                if (publicProblem != null) MaterialTheme.colorScheme.error else Color.Unspecified,
        ) {
            change(card.copy(showPublic = it))
        }
        Section("A tap copies")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Copies.entries.forEachIndexed { index, copies ->
                SegmentedButton(
                    selected = card.copies == copies,
                    onClick = { change(card.copy(copies = copies)) },
                    shape = SegmentedButtonDefaults.itemShape(index, Copies.entries.size),
                ) {
                    Text(copies.title)
                }
            }
        }
        Section("Look")
        Looks(card.style) { change(card.copy(style = it)) }
    }
}

/**
 * The look: the same as one of the person's bars, colours and corners and all, or one of the colour
 * sets bars offer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Looks(style: Style, onChange: (Style) -> Unit) {
    val context = LocalContext.current
    val bars = remember { context.droidective.layouts.all().filter { it.kind == LayoutKind.Bar } }
    if (bars.isNotEmpty()) {
        Text("Same as", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bars.forEach { bar ->
                FilterChip(
                    selected = sameLook(bar.style, style),
                    onClick = { onChange(lookOf(bar.style)) },
                    label = { Text(bar.name) },
                )
            }
        }
    }
    Text("Colours", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StylePreset.entries
            .filter {
                it != StylePreset.Wallpaper || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            }
            .forEach { preset ->
                FilterChip(
                    selected = preset.isOn(style),
                    onClick = { onChange(preset.applyTo(style)) },
                    label = { Text(preset.title) },
                )
            }
    }
}

/** A bar's style as far as this widget draws it: colours and corners, not columns or labels. */
private fun lookOf(bar: Style): Style =
    Style()
        .copy(
            background = bar.background,
            iconOn = bar.iconOn,
            iconOff = bar.iconOff,
            labelColor = bar.labelColor,
            dynamicColors = bar.dynamicColors,
            cornerRadius = bar.cornerRadius,
        )

private fun sameLook(bar: Style, style: Style): Boolean = lookOf(bar) == lookOf(style)

/** The widget as it will draw, with this phone's network, in its own colours and corners. */
@Composable
private fun Preview(now: NetworkNow?, card: NetworkCard) {
    val shown = now ?: NetworkNow(Transport.Other)
    val style = card.style
    val dynamic = style.dynamicColors
    val background =
        if (dynamic) MaterialTheme.colorScheme.surfaceContainerHigh else Color(style.background)
    val accent = if (dynamic) MaterialTheme.colorScheme.primary else Color(style.iconOn)
    val label =
        if (dynamic) MaterialTheme.colorScheme.onSurfaceVariant
        else Color(style.labelColor ?: style.iconOff)
    val value =
        if (dynamic) MaterialTheme.colorScheme.onSurface
        else Color(style.labelColor ?: contrastOf(style.background))
    Surface(
        shape = RoundedCornerShape(style.cornerRadius.dp),
        color = background,
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
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (now == null) "Reading…" else NetworkCards.title(shown),
                        style = MaterialTheme.typography.labelLarge,
                        color = label,
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
                        color = value,
                    )
                } else {
                    Text(
                        line.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = label,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        line.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = value,
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
