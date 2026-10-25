package com.droidective.android.ui.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.proxy.CertificateTrust
import com.droidective.android.proxy.Hold
import com.droidective.android.proxy.Holds
import com.droidective.android.proxy.TrafficExport
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.Symbols
import com.droidective.core.net.proxy.Breakpoint
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.FlowFilter
import com.droidective.core.net.proxy.FlowLabels
import com.droidective.core.net.proxy.FlowOutcome
import com.droidective.core.net.proxy.FlowSummary
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.Remap
import com.droidective.core.net.proxy.TrafficRules
import com.droidective.core.net.proxy.Unread
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
    var flows by remember { mutableStateOf(TrafficProxy.flows.all()) }
    LaunchedEffect(Unit) {
        TrafficProxy.changes.collect {
            flows = TrafficProxy.flows.all()
            delay(PACE_MILLIS)
        }
    }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    // The list keeps its own state — the search, the scroll, what is open — while an editor or a
    // flow has the screen, as a screen further back would.
    val kept = rememberSaveableStateHolder()
    editing?.let { now ->
        val back = ToolContext(tool.snackbar, onBack = { editing = null }, tool.onOpenSetup)
        when (now) {
            is Editing.OfMock -> key(now.mock.id) { MockEditor(now.mock, now.isNew, back) }
            is Editing.OfBreakpoint ->
                key(now.point.id) { BreakpointEditor(now.point, now.isNew, back) }
            is Editing.OfRemap -> key(now.remap.id) { RemapEditor(now.remap, now.isNew, back) }
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
            onRemap = { editing = Editing.OfRemap(it, isNew = true) },
        )
        return
    }
    kept.SaveableStateProvider(LIST) {
        TrafficMain(
            tool,
            flows,
            onOpen = { open = it },
            onHold = { holding = it },
            onEdit = { editing = it },
        )
    }
}

/** Where the tool's parts live, as its tabs name them. */
private enum class TrafficTab(val title: String) {
    Requests("Requests"),
    Rules("Rules"),
    Mocks("Mocks"),
    Certificate("Certificate"),
}

/**
 * The header with the proxy's chip, then the tabs: what the apps sent, which are read, what's
 * mocked, and the certificate.
 */
@Composable
private fun TrafficMain(
    tool: ToolContext,
    flows: List<Flow>,
    onOpen: (Long) -> Unit,
    onHold: (Hold) -> Unit,
    onEdit: (Editing) -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(TrafficTab.Requests.name) }
    var proxy by rememberSaveable { mutableStateOf(false) }
    // The app a fix was for, to read again once it trusts the certificate; null uid for any app.
    var trusting by remember { mutableStateOf<Trusting?>(null) }
    val tab = TrafficTab.valueOf(tabName)
    val tabs = rememberSaveableStateHolder()
    val trust = rememberTrust()
    val say = rememberSay(tool)
    BackHandler(onBack = tool.onBack)
    Scaffold(snackbarHost = { SnackbarHost(tool.snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolTitle("Network Traffic", tool)
                Spacer(Modifier.weight(1f))
                TrafficStatusChip(onClick = { proxy = true })
            }
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                TrafficTab.entries.forEach { each ->
                    Tab(
                        selected = tab == each,
                        onClick = { tabName = each.name },
                        text = { Text(each.title, maxLines = 1) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            tabs.SaveableStateProvider(tab.name) {
                when (tab) {
                    TrafficTab.Requests ->
                        RequestsTab(
                            tool,
                            flows,
                            Requesting(
                                onOpen,
                                onHold,
                                onTrust = { uid -> trusting = Trusting(uid) },
                                onCertificate = { tabName = TrafficTab.Certificate.name },
                            ),
                            trust,
                        )
                    TrafficTab.Rules -> RulesTab(flows)
                    TrafficTab.Mocks ->
                        MocksTab(
                            onEditMock = { mock, isNew -> onEdit(Editing.OfMock(mock, isNew)) },
                            onEditBreakpoint = { point, isNew ->
                                onEdit(Editing.OfBreakpoint(point, isNew))
                            },
                            onEditRemap = { remap, isNew -> onEdit(Editing.OfRemap(remap, isNew)) },
                        )
                    TrafficTab.Certificate -> CertificateTab(trust, say)
                }
            }
        }
    }
    if (proxy) ProxySheet(tool, onDismiss = { proxy = false })
    trusting?.let { TrustSheet(it.uid, say, onDismiss = { trusting = null }) }
}

/** What the Requests tab can open: a request, a held exchange, a fix, the certificate. */
private class Requesting(
    val onOpen: (Long) -> Unit,
    val onHold: (Hold) -> Unit,
    val onTrust: (Int?) -> Unit,
    val onCertificate: () -> Unit,
)

/** The app a certificate fix is for, by uid when it's known. */
private class Trusting(val uid: Int?)

/**
 * The Requests tab: what's held, what wasn't read, then the search and the requests, newest first;
 * with nothing yet, how to start.
 */
@Composable
private fun RequestsTab(
    tool: ToolContext,
    flows: List<Flow>,
    opens: Requesting,
    trust: CertificateTrust?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val held by Holds.held.collectAsStateWithLifecycle()
    val rules by TrafficProxy.rules.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val filter = remember(query) { FlowFilter.parse(query) }
    val visible = remember(flows, filter) { flows.filter(filter::matches).asReversed() }
    val labels = remember { AppLabels(context) }
    Column(Modifier.fillMaxSize()) {
        if (flows.isNotEmpty()) SearchRow(query, flows, labels) { query = it }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (held.isNotEmpty()) item(key = "held") { HeldCard(held, opens.onHold) }
            item(key = "unread") {
                UnreadBanner(flows, labels) { app ->
                    opens.onTrust(flows.lastOrNull { it.app == app }?.uid)
                }
            }
            if (flows.isEmpty()) {
                item(key = "watch") {
                    WatchCard(trust, opens.onCertificate, onTrust = { opens.onTrust(null) })
                }
            } else {
                // First in the list, so a list read from the top keeps showing the newest.
                item(key = "count") {
                    Count(
                        "${visible.size} of ${flows.size} · ${held()}",
                        onShare =
                            if (visible.any { !it.opening }) {
                                { scope.launch { exportHar(context, tool, visible.reversed()) } }
                            } else {
                                null
                            },
                    )
                }
            }
            items(visible, key = { it.id }) { flow ->
                FlowRow(flow, labels, rules) { opens.onOpen(flow.id) }
            }
        }
    }
}

/** How many requests show of how many, how much is kept, and Share as HAR and Clear. */
@Composable
private fun Count(text: String, onShare: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (onShare != null) {
            // What the search shows, oldest first, the way HAR readers lay a page out.
            IconButton(onClick = onShare) {
                Icon(painterResource(Symbols.byName.getValue("share").outlined), "Share as HAR")
            }
        }
        TextButton(onClick = TrafficProxy::clear) {
            Icon(
                painterResource(Symbols.byName.getValue("delete_sweep").outlined),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Clear")
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

@Composable
private fun SearchRow(
    query: String,
    flows: List<Flow>,
    labels: AppLabels,
    onQuery: (String) -> Unit,
) {
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search, or status:4xx") },
            leadingIcon = {
                Icon(painterResource(Symbols.byName.getValue("search").outlined), null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(
                            painterResource(Symbols.byName.getValue("close").outlined),
                            "Clear the search",
                        )
                    }
                }
            },
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
private fun FlowRow(flow: Flow, labels: AppLabels, rules: TrafficRules, onClick: () -> Unit) {
    val outcome = FlowLabels.outcome(flow)
    val operation =
        remember(flow.id, flow.requestBody) {
            FlowLabels.graphQlOperation(flow) ?: FlowLabels.grpcMethod(flow)
        }
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
                rowDetails(flow, operation, labels, rules),
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

/**
 * The app, the host when the first line named an operation instead, and how long and how big; for a
 * call that wasn't read, why, in a few words.
 */
private fun rowDetails(
    flow: Flow,
    operation: String?,
    labels: AppLabels,
    rules: TrafficRules,
): String {
    val parts = mutableListOf<String>()
    flow.app?.let { parts += labels.of(it) }
    if (operation != null) parts += flow.authority
    Unread.of(flow, rules)?.let {
        parts += Unread.label(it)
        return parts.joinToString(" · ")
    }
    flow.failure?.let {
        parts += it
        return parts.joinToString(" · ")
    }
    FlowLabels.grpc(flow)?.takeUnless { it.ok }?.let { parts += it.name }
    if (flow.mocked != null) parts += "mocked"
    if (flow.edited) parts += "changed" else if (flow.paused != null) parts += "held"
    flow.sentTo?.let { parts += "sent to ${it.substringAfter("://")}" }
    if (flow.socket) parts += messages(TrafficProxy.messages.count(flow.id))
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

private fun messages(count: Int) = if (count == 1) "1 message" else "$count messages"

internal fun Context.openSettings(action: String) {
    try {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Package names to labels, and whether each is a debuggable build, asked of the system once each: a
 * list redraws them on every scroll.
 */
internal class AppLabels(private val context: Context) {
    private val known = HashMap<String, String>()
    private val debuggable = HashMap<String, Boolean>()

    fun of(packageName: String): String =
        known.getOrPut(packageName) { CrashAlerts.label(context, packageName) }

    /** Whether it's an app being developed: a debug build, which can trust the certificate. */
    fun debuggable(packageName: String): Boolean =
        debuggable.getOrPut(packageName) {
            try {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
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
private const val LIST = "list"
private const val MAX_APP_CHIPS = 6
private const val STATUS_WIDTH = 52

/** A mock, a remap or a breakpoint open in its editor, and whether saving it makes a new one. */
private sealed interface Editing {
    class OfMock(val mock: Mock, val isNew: Boolean) : Editing

    class OfBreakpoint(val point: Breakpoint, val isNew: Boolean) : Editing

    class OfRemap(val remap: Remap, val isNew: Boolean) : Editing
}
