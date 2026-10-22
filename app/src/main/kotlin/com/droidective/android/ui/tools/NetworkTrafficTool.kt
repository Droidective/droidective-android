package com.droidective.android.ui.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.proxy.CertificateTrust
import com.droidective.android.proxy.Hold
import com.droidective.android.proxy.Holds
import com.droidective.android.proxy.ProxyRun
import com.droidective.android.proxy.TrafficExport
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.copy
import com.droidective.core.AccessLevel
import com.droidective.core.net.proxy.Breakpoint
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.FlowFilter
import com.droidective.core.net.proxy.FlowLabels
import com.droidective.core.net.proxy.FlowOutcome
import com.droidective.core.net.proxy.FlowSummary
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.Throttle
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The debugging proxy: what this phone's apps send and what comes back, read on the phone itself.
 * The list reaches the screen four times a second at most, however busy the phone is, so a page of
 * images loading can't make it lay out on every request.
 */
@Composable
fun NetworkTrafficTool(tool: ToolContext) {
    val context = LocalContext.current
    var flows by remember { mutableStateOf(TrafficProxy.flows.all()) }
    LaunchedEffect(Unit) {
        TrafficProxy.changes.collect {
            flows = TrafficProxy.flows.all()
            delay(PACE_MILLIS)
        }
    }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    // Out here, before an editor or a flow takes the screen: the list's own state goes while one
    // is open, and coming back would fold the rules up and scroll to the top.
    var rulesOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf<Editing?>(null) }
    editing?.let { now ->
        val back = ToolContext(tool.snackbar, onBack = { editing = null }, tool.onOpenSetup)
        when (now) {
            is Editing.OfMock -> key(now.mock.id) { MockEditor(now.mock, now.isNew, back) }
            is Editing.OfBreakpoint ->
                key(now.point.id) { BreakpointEditor(now.point, now.isNew, back) }
        }
        return
    }
    val held by Holds.held.collectAsStateWithLifecycle()
    var holding by remember { mutableStateOf<Hold?>(null) }
    // Decided elsewhere, from the notification, or let go when nobody did in time.
    val hold = holding?.takeIf { it in held }
    if (hold != null) {
        val back = ToolContext(tool.snackbar, onBack = { holding = null }, tool.onOpenSetup)
        key(hold) { HeldEditor(hold, back) }
        return
    }
    // The list catches up four times a second; a flow just made, a repeat, is in the store first.
    val opened = open?.let { id -> flows.firstOrNull { it.id == id } ?: TrafficProxy.flows.get(id) }
    if (opened != null) {
        FlowDetail(
            opened,
            ToolContext(tool.snackbar, onBack = { open = null }, tool.onOpenSetup),
            onOpen = { open = it },
            onMock = { editing = Editing.OfMock(it, isNew = true) },
            onBreak = { editing = Editing.OfBreakpoint(it, isNew = true) },
        )
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    val filter = remember(query) { FlowFilter.parse(query) }
    val visible = remember(flows, filter) { flows.filter(filter::matches).asReversed() }
    val labels = remember { AppLabels(context) }

    BackHandler(onBack = tool.onBack)
    Scaffold(snackbarHost = { SnackbarHost(tool.snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { TitleRow(tool, flows.isNotEmpty(), visible) }
            if (held.isNotEmpty()) item { HeldCard(held) { holding = it } }
            item { ProxySection(tool) }
            item { CertificateSection(tool) }
            item {
                RulesSection(
                    flows,
                    expanded = rulesOpen,
                    onExpand = { rulesOpen = it },
                    onEditMock = { mock, isNew -> editing = Editing.OfMock(mock, isNew) },
                    onEditBreakpoint = { point, isNew ->
                        editing = Editing.OfBreakpoint(point, isNew)
                    },
                )
            }
            item { SearchRow(query, flows, labels) { query = it } }
            item {
                ToolNote(
                    if (flows.isEmpty()) "Nothing yet. Requests show here as apps make them."
                    else "${visible.size} of ${flows.size} · ${held()}"
                )
            }
            items(visible, key = { it.id }) { flow -> FlowRow(flow, labels) { open = flow.id } }
        }
    }
}

@Composable
private fun TitleRow(tool: ToolContext, any: Boolean, visible: List<Flow>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolTitle("Network Traffic", tool)
        Box(Modifier.weight(1f))
        if (visible.any { !it.opening }) {
            // What the search shows, oldest first, the way HAR readers lay a page out.
            IconButton(
                onClick = { scope.launch { exportHar(context, tool, visible.reversed()) } }
            ) {
                Icon(painterResource(Symbols.byName.getValue("share").outlined), "Share as HAR")
            }
        }
        if (any) {
            IconButton(onClick = TrafficProxy::clear) {
                Icon(painterResource(Symbols.byName.getValue("delete_sweep").outlined), "Clear")
            }
        }
    }
}

private suspend fun exportHar(context: Context, tool: ToolContext, flows: List<Flow>) {
    val saved =
        try {
            TrafficExport.save(context, flows)
        } catch (e: IOException) {
            tool.say("It couldn't be saved: ${e.message}")
            return
        }
    if (saved == null) {
        tool.say("Android didn't make room for it in Downloads.")
        return
    }
    context.startActivity(TrafficExport.share(saved))
}

/** Start and stop, and whether the phone is sending its traffic here. */
@Composable
private fun ProxySection(tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val run by TrafficProxy.state.collectAsStateWithLifecycle()
    val pointing by TrafficProxy.pointing.collectAsStateWithLifecycle()
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val canPoint = access.allows(AccessLevel.Granted)
    LifecycleResumeEffect(Unit) {
        TrafficProxy.refreshPointing(context)
        onPauseOrDispose {}
    }
    ToolSection("Proxy") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (val now = run) {
                    ProxyRun.Off -> "Off"
                    ProxyRun.Starting -> "Starting…"
                    is ProxyRun.Listening -> "Listening on ${TrafficProxy.ADDRESS}"
                    is ProxyRun.Failed -> now.message
                },
                color =
                    if (run is ProxyRun.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (run is ProxyRun.Listening) {
                OutlinedButton(onClick = { TrafficProxy.stop(context) }) { Text("Stop") }
            } else {
                Button(
                    onClick = { TrafficProxy.start(context, point = canPoint) },
                    enabled = run !is ProxyRun.Starting,
                ) {
                    Text("Start")
                }
            }
        }
        if (run !is ProxyRun.Listening && pointing) StrandedNote(tool)
        if (run is ProxyRun.Listening) {
            SpeedRow()
            if (canPoint) {
                PointRow(pointing) { here ->
                    scope.launch {
                        if (!TrafficProxy.point(context, here)) {
                            tool.say("The phone's proxy setting didn't change.")
                        }
                    }
                }
            } else {
                ManualPointing(tool)
            }
        } else {
            ToolNote(
                "Start points the phone here. While it does, an app that doesn't trust the " +
                    "certificate can't reach its HTTPS hosts. Stop puts the phone's proxy setting " +
                    "back the way it was."
            )
        }
    }
}

/** How slow a network to play, for seeing an app's loading states. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedRow() {
    val throttle by TrafficProxy.throttle.collectAsStateWithLifecycle()
    Text(
        "Network speed",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Throttle.presets.forEach { preset ->
            FilterChip(
                selected = preset.throttle == throttle,
                onClick = { TrafficProxy.throttle(preset.throttle) },
                label = { Text(preset.name) },
            )
        }
    }
    if (!throttle.isOff) {
        ToolNote(
            "Every request through the proxy waits ${throttle.latencyMillis} ms and arrives at " +
                "${FlowLabels.size(throttle.downBytesPerSecond)}/s, the apps passed through too."
        )
    }
}

/** The proxy has gone and the phone still points at it, so no app can reach the network. */
@Composable
private fun StrandedNote(tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Text(
        "This phone is still pointed at the proxy, so apps can't reach the network.",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    OutlinedButton(
        onClick = {
            scope.launch {
                if (!TrafficProxy.point(context, here = false)) {
                    tool.say("It needs Granted or Shell to change.", offerSetup = true)
                }
            }
        }
    ) {
        Text("Take it off")
    }
}

@Composable
private fun PointRow(pointing: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!pointing) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Send this phone's traffic here")
            Text(
                if (pointing) "Every app that uses the system proxy comes through here."
                else "Off: only apps set up to use ${TrafficProxy.ADDRESS} come through.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = pointing, onCheckedChange = onChange)
    }
}

/** Without Granted or Shell the app can't change the proxy setting, but the person can. */
@Composable
private fun ManualPointing(tool: ToolContext) {
    val context = LocalContext.current
    ToolNote(
        "To send Wi-Fi traffic here, open the Wi-Fi network's settings, set Proxy to Manual, " +
            "host 127.0.0.1, port ${TrafficProxy.PORT}. With Granted or Shell, Droidective does " +
            "it for you and puts it back when you stop."
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { context.openSettings(Settings.ACTION_WIFI_SETTINGS) }) {
            Text("Open Wi-Fi settings")
        }
        OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
    }
}

/** Whether the phone trusts the certificate, and how to get it to. */
@Composable
private fun CertificateSection(tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var trust by remember { mutableStateOf<CertificateTrust?>(null) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        // Back from Settings, where it may just have been installed.
        resumes++
        onPauseOrDispose {}
    }
    LaunchedEffect(resumes) { trust = TrafficProxy.trust(context) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // What there is to expand changes with the answer, from the steps to the debug-build config,
    // so a card left open for one closes when the other takes its place.
    var settled by rememberSaveable { mutableStateOf<CertificateTrust?>(null) }
    LaunchedEffect(trust) {
        val now = trust ?: return@LaunchedEffect
        if (settled != null && settled != now) {
            expanded = false
            // What an app refused was refused under the old answer, and may not be refused now.
            TrafficProxy.refusals.clear()
        }
        settled = now
    }
    ToolSection("Certificate") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (trust) {
                    null -> "Checking…"
                    CertificateTrust.None -> "Not installed: HTTPS can't be read"
                    CertificateTrust.User -> "Installed as a user certificate"
                    CertificateTrust.System -> "Installed in the system store"
                },
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (trust == CertificateTrust.None || trust == CertificateTrust.User) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        when {
                            expanded -> "Hide"
                            trust == CertificateTrust.None -> "Set up"
                            else -> "Your app"
                        }
                    )
                }
            }
        }
        when {
            trust == CertificateTrust.System ->
                ToolNote("Every app that doesn't pin its certificates can be read.")
            !expanded -> Unit
            trust == CertificateTrust.None -> InstallSteps(tool, scope, context)
            trust == CertificateTrust.User -> UserTrust()
        }
    }
}

@Composable
private fun InstallSteps(
    tool: ToolContext,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
) {
    ToolNote(
        "Reading HTTPS needs the phone to trust Droidective's certificate. Android doesn't let an " +
            "app install one, so it takes two steps:"
    )
    ToolNote("1. Save the certificate to Downloads.")
    OutlinedButton(
        onClick = {
            scope.launch {
                val saved =
                    try {
                        TrafficProxy.saveCertificate(context)
                    } catch (e: IOException) {
                        tool.say("It couldn't be saved: ${e.message}")
                        return@launch
                    }
                tool.say(
                    if (saved != null) "Saved to Downloads as droidective-proxy-ca.crt"
                    else "Android didn't make room for it in Downloads."
                )
            }
        }
    ) {
        Text("Save certificate")
    }
    ToolNote(
        "2. In Settings, search for \"CA certificate\", choose Install anyway, and pick " +
            "droidective-proxy-ca.crt. Android asks for a screen lock first if there isn't one."
    )
    OutlinedButton(onClick = { context.openSettings(Settings.ACTION_SECURITY_SETTINGS) }) {
        Text("Open security settings")
    }
}

/** A user certificate: trusted by apps that say so, which is what a debug build is for. */
@Composable
private fun UserTrust() {
    val context = LocalContext.current
    ToolNote(
        "Since Android 7, an app trusts a user certificate only if it says so. To read your own " +
            "app, add this to its debug build's network security config:"
    )
    Text(
        DEBUG_OVERRIDES,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
    )
    OutlinedButton(onClick = { context.copy("Network security config", DEBUG_OVERRIDES) }) {
        Text("Copy")
    }
    ToolNote(
        "An app that pins its certificates refuses this one either way. That's the app working " +
            "as it should."
    )
}

@Composable
private fun SearchRow(
    query: String,
    flows: List<Flow>,
    labels: AppLabels,
    onQuery: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search, or app: host: status:4xx is:failed") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val apps = remember(flows) { FlowSummary.apps(flows).take(MAX_APP_CHIPS) }
        if (apps.isNotEmpty() || flows.any { it.failure != null }) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                apps.forEach { (packageName, count) ->
                    val term = "${FlowFilter.APP}:$packageName"
                    FilterChip(
                        selected = FlowFilter.holds(query, term),
                        onClick = { onQuery(FlowFilter.toggled(query, term)) },
                        label = { Text("${labels.of(packageName)} $count") },
                    )
                }
                val failed = "${FlowFilter.STATE}:failed"
                FilterChip(
                    selected = FlowFilter.holds(query, failed),
                    onClick = { onQuery(FlowFilter.toggled(query, failed)) },
                    label = { Text("Failed") },
                )
            }
        }
    }
}

@Composable
private fun FlowRow(flow: Flow, labels: AppLabels, onClick: () -> Unit) {
    val outcome = FlowLabels.outcome(flow)
    val operation = remember(flow.id, flow.requestBody) { FlowLabels.graphQlOperation(flow) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(STATUS_WIDTH.dp)) {
            Text(
                FlowLabels.status(flow),
                color = outcomeColor(outcome),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                flow.request.method,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                operation ?: (flow.authority + FlowLabels.path(flow)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                rowDetails(flow, operation, labels),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (outcome == FlowOutcome.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The app, the host when the first line named an operation instead, and how long and how big. */
private fun rowDetails(flow: Flow, operation: String?, labels: AppLabels): String {
    val parts = mutableListOf<String>()
    flow.app?.let { parts += labels.of(it) }
    if (operation != null) parts += flow.authority
    flow.failure?.let {
        parts += it
        return parts.joinToString(" · ")
    }
    FlowLabels.passed(flow)?.let { parts += it }
    if (flow.mocked != null) parts += "mocked"
    if (flow.edited) parts += "changed" else if (flow.paused != null) parts += "held"
    flow.repeatOf?.let { parts += "repeat of #$it" }
    flow.durationMillis?.let { parts += FlowLabels.duration(it) }
    if (flow.wireLength > 0) parts += FlowLabels.size(flow.wireLength)
    return parts.joinToString(" · ")
}

@Composable
internal fun outcomeColor(outcome: FlowOutcome): Color =
    when (outcome) {
        FlowOutcome.Success -> MaterialTheme.colorScheme.primary
        FlowOutcome.Redirect,
        FlowOutcome.Informational -> MaterialTheme.colorScheme.tertiary
        FlowOutcome.ClientError,
        FlowOutcome.ServerError,
        FlowOutcome.Failed -> MaterialTheme.colorScheme.error
        FlowOutcome.Pending,
        FlowOutcome.PassedThrough -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun held(): String = "${FlowLabels.size(TrafficProxy.flows.heldBytes())} held"

internal fun Context.openSettings(action: String) {
    try {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Package names to labels, asked of the system once each: a list redraws them on every scroll. */
internal class AppLabels(private val context: Context) {
    private val known = HashMap<String, String>()

    fun of(packageName: String): String =
        known.getOrPut(packageName) { CrashAlerts.label(context, packageName) }
}

internal val DEBUG_OVERRIDES =
    """
    <network-security-config>
      <debug-overrides>
        <trust-anchors>
          <certificates src="system" />
          <certificates src="user" />
        </trust-anchors>
      </debug-overrides>
    </network-security-config>
    """
        .trimIndent()

private const val PACE_MILLIS = 250L
private const val MAX_APP_CHIPS = 6
private const val STATUS_WIDTH = 52

/** A mock or a breakpoint open in its editor, and whether saving it makes a new one. */
private sealed interface Editing {
    class OfMock(val mock: Mock, val isNew: Boolean) : Editing

    class OfBreakpoint(val point: Breakpoint, val isNew: Boolean) : Editing
}
