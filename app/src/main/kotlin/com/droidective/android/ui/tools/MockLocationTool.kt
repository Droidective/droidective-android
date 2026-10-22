package com.droidective.android.ui.tools

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.location.MockLocation
import com.droidective.android.ui.Symbols
import com.droidective.core.apps.AppOps
import com.droidective.core.location.Coordinates
import com.droidective.core.location.Place
import com.droidective.core.location.Places
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mock Location: where the phone says it is. A place goes in as coordinates or a map link, from the
 * ones kept, or from a few cities ready for testing what changes by country; Stop gives the phone
 * back its own.
 */
@Composable
fun MockLocationTool(tool: ToolContext) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { MockLocation.places.load(context) } }
    ToolScaffold("Mock Location", tool) {
        NowSection()
        ChosenSection(tool)
        WhereSection(tool)
        KeptSection()
        ReadySection()
        ToolNote(
            "Apps see a mock location as one: an app can ask (Location.isMock), and some — " +
                "banking, games — refuse it. Google's own fused location follows the providers " +
                "this stands in for."
        )
    }
}

@Composable
private fun NowSection() {
    val context = LocalContext.current
    val state by MockLocation.state.collectAsStateWithLifecycle()
    ToolSection("Now") {
        when (val now = state) {
            is MockLocation.State.On -> {
                InfoRow(
                    "The phone is at",
                    listOfNotNull(now.name, now.where.toString()).joinToString("\n"),
                    mono = true,
                )
                OutlinedButton(onClick = { MockLocation.stop(context) }) { Text("Stop") }
            }
            is MockLocation.State.Failed -> ToolNote(now.why)
            MockLocation.State.Off -> ToolNote("The phone reports where it really is.")
        }
    }
}

/** Whether Droidective may stand in, and the two ways to let it. */
@Composable
private fun ChosenSection(tool: ToolContext) {
    val context = LocalContext.current
    val state by MockLocation.state.collectAsStateWithLifecycle()
    var checks by remember { mutableIntStateOf(0) }
    val chosen = remember(checks, state) { MockLocation.chosen(context) }
    if (chosen) return
    val shell = rememberToolShell(tool)
    ToolSection("First, once") {
        ToolNote(
            "Android lets one app stand in for the phone's location: the mock location app, " +
                "picked in Developer options. Droidective isn't it yet."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    shell.run(
                        "Make it the mock location app",
                        listOf(AppOps.set(context.packageName, MOCK_LOCATION_OP, "allow")),
                        "Droidective is the mock location app",
                    ) {
                        checks++
                    }
                }
            ) {
                Text("Pick Droidective")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            ) {
                Text("Developer options")
            }
        }
        ToolNote(
            "Pick Droidective uses the shell. Where the shell may not set it — OxygenOS — choose " +
                "Droidective under Developer options ▸ Select mock location app, then come back."
        )
        TextButton(onClick = { checks++ }) { Text("I picked it there") }
    }
}

/** A place typed or pasted: coordinates, a map link, degrees and minutes. */
@Composable
private fun WhereSection(tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val say = rememberSay(tool)
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) { Coordinates.parse(text) }
    ToolSection("Go somewhere") {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("51.5074, -0.1278 or a map link") },
            supportingText = {
                Text(
                    when {
                        text.isBlank() ->
                            "Coordinates, a Google Maps or OpenStreetMap link, or geo:"
                        parsed == null -> "That isn't a place on the Earth"
                        else -> parsed.toString()
                    }
                )
            },
            isError = text.isNotBlank() && parsed == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name, to keep it") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    parsed?.let { MockLocation.start(context, it, name.ifBlank { null }) }
                },
                enabled = parsed != null,
            ) {
                Text("Go there")
            }
            OutlinedButton(
                onClick = {
                    val where = parsed ?: return@OutlinedButton
                    val place = Place(UUID.randomUUID().toString(), name.trim(), where)
                    scope.launch(Dispatchers.IO) { MockLocation.places.save(context, place) }
                    say("Kept ${place.name}")
                    name = ""
                },
                enabled = parsed != null && name.isNotBlank(),
            ) {
                Text("Keep it")
            }
        }
    }
}

@Composable
private fun KeptSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kept by MockLocation.places.items.collectAsStateWithLifecycle()
    if (kept.isEmpty()) return
    ToolSection("Kept") {
        kept.forEach { place ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaceRow(place, Modifier.weight(1f))
                IconButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            MockLocation.places.remove(context, place.id)
                        }
                    }
                ) {
                    Icon(painterResource(Symbols.byName.getValue("delete").outlined), "Forget")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadySection() {
    val context = LocalContext.current
    ToolSection("Ready") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Places.ready.forEach { place ->
                AssistChip(
                    onClick = { MockLocation.start(context, place.where, place.name) },
                    label = { Text(place.name) },
                )
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .clickable { MockLocation.start(context, place.where, place.name) }
            .padding(vertical = 6.dp)
    ) {
        Text(place.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            place.where.toString(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The app-op Developer options sets for the mock location app. */
private const val MOCK_LOCATION_OP = "MOCK_LOCATION"
