package com.droidective.android.ui.tools

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.automations.AppChooser
import com.droidective.core.AccessLevel
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.Refusals
import com.droidective.core.net.proxy.TrafficRules
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which apps and hosts are read and which are left alone, kept across runs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RulesSection(flows: List<Flow>, onEditMock: (Mock, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { TrafficProxy.loadRules(context) } }
    val rules by TrafficProxy.rules.collectAsStateWithLifecycle()
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val labels = remember { AppLabels(context) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val change = { new: TrafficRules -> scope.saveRules(context, new) }
    ToolSection("Rules") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(summary(rules, labels), modifier = Modifier.weight(1f).padding(start = 4.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Edit")
            }
        }
        if (!expanded) return@ToolSection
        Label("Apps to read")
        if (rules.apps.isEmpty()) ToolNote("Every app. Choose one to leave every other app alone.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rules.apps.forEach { packageName ->
                Removable(labels.of(packageName)) { change(rules.withoutApp(packageName)) }
            }
            AssistChip(onClick = { choosing = true }, label = { Text("Choose an app") })
        }
        if (rules.apps.isNotEmpty() && !access.allows(AccessLevel.Shell)) {
            ToolNote(
                "Telling apps apart needs Shell. Without it no connection's app is known, so " +
                    "nothing is read."
            )
        }
        Patterns(
            title = "Pass these hosts through",
            note = "Never read, for any app. For hosts an app pins, and anything private.",
            patterns = rules.passThrough,
            onAdd = { change(rules.passingThrough(it)) },
            onRemove = { change(rules.notPassingThrough(it)) },
        )
        Patterns(
            title = "Read only these hosts",
            note = "Empty reads every host. *.example.com is its subdomains, * is every host.",
            patterns = rules.readOnly,
            onAdd = { change(rules.readingOnly(it)) },
            onRemove = { change(rules.notReadingOnly(it)) },
        )
        StepAsideRow(rules.stepAside) { change(rules.copy(stepAside = it)) }
        Refused(flows, labels)
        Mocks(onEditMock)
    }
    if (choosing) {
        AppChooser(
            title = "Read which app",
            onDismiss = { choosing = false },
            onPick = {
                change(rules.withApp(it.packageName))
                choosing = false
            },
        )
    }
}

private fun summary(rules: TrafficRules, labels: AppLabels): String {
    val apps =
        when (rules.apps.size) {
            0 -> "Reading every app"
            1 -> "Reading ${labels.of(rules.apps.single())}"
            else -> "Reading ${rules.apps.size} apps"
        }
    val hosts = rules.passThrough.size
    return if (hosts == 0) apps else "$apps · ${hosts.hosts()} passed through"
}

private fun Int.hosts(): String = if (this == 1) "1 host" else "$this hosts"

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun Removable(text: String, onRemove: () -> Unit) {
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(text) },
        trailingIcon = {
            Icon(painterResource(Symbols.byName.getValue("close").outlined), "Remove $text")
        },
    )
}

/** A list of host patterns with a field to add one; what can't be a host is refused. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Patterns(
    title: String,
    note: String,
    patterns: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var typed by rememberSaveable(title) { mutableStateOf("") }
    val pattern = TrafficRules.pattern(typed)
    val add = {
        pattern?.let(onAdd)
        typed = ""
    }
    Label(title)
    ToolNote(note)
    if (patterns.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            patterns.forEach { Removable(it) { onRemove(it) } }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = { Text("api.example.com") },
            singleLine = true,
            isError = typed.isNotBlank() && pattern == null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = add, enabled = pattern != null) { Text("Add") }
    }
}

@Composable
private fun StepAsideRow(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Step aside for apps that refuse the certificate")
            Text(
                "After an app turns it down for a host, its next connections there pass through, " +
                    "so it keeps working.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** What is passed through this run because an app refused; each can be read again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Refused(flows: List<Flow>, labels: AppLabels) {
    val context = LocalContext.current
    var forgotten by remember { mutableIntStateOf(0) }
    // Every refusal arrives as a flow, so a new list is when there may be a new one.
    val refused = remember(flows, forgotten) { TrafficProxy.refusals.all() }
    if (refused.isEmpty()) return
    Label("Passed through after a refusal")
    ToolNote("Forget one to read it again, once the app trusts the certificate.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        refused.forEach { refusal ->
            Removable(describe(context, refusal, labels)) {
                TrafficProxy.refusals.forget(refusal)
                forgotten++
            }
        }
    }
    TextButton(
        onClick = {
            TrafficProxy.refusals.clear()
            forgotten++
        }
    ) {
        Text("Forget all")
    }
}

private fun describe(context: Context, refusal: Refusals.Refusal, labels: AppLabels): String {
    val app = refusal.uid?.let { TrafficProxy.appOf(context, it) }?.let(labels::of)
    return if (app == null) refusal.host else "${refusal.host} · $app"
}

internal fun CoroutineScope.saveRules(context: Context, rules: TrafficRules) {
    launch(Dispatchers.IO) { TrafficProxy.setRules(context, rules) }
}

/** The answers given instead of the server's, each to edit or switch off, and a way to add one. */
@Composable
private fun Mocks(onEdit: (Mock, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { TrafficProxy.loadMocks(context) } }
    val mocks by TrafficProxy.mocks.collectAsStateWithLifecycle()
    Label("Mocked answers")
    ToolNote(
        if (mocks.isEmpty()) "None yet. Mock a response from a request in the list, or start one."
        else "Matching requests are answered here and never reach the server."
    )
    mocks.forEach { mock ->
        MockRow(
            mock,
            onEdit = { onEdit(mock, false) },
            onEnable = { on ->
                scope.launch(Dispatchers.IO) {
                    TrafficProxy.saveMock(context, mock.copy(enabled = on))
                }
            },
        )
    }
    TextButton(onClick = { onEdit(Mock(UUID.randomUUID().toString(), url = "https://"), true) }) {
        Text("New mock")
    }
}
