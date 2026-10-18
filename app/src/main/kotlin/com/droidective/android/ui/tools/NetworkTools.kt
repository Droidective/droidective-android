package com.droidective.android.ui.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.droidective.android.droidective
import com.droidective.android.surface.Surfaces
import com.droidective.android.surface.ToggleTaps
import com.droidective.android.ui.copy
import com.droidective.android.ui.openSafely
import com.droidective.android.ui.runInForeground
import com.droidective.core.device.Rate
import com.droidective.core.device.RateMeter
import com.droidective.core.net.Addresses
import com.droidective.core.toggles.Param
import com.droidective.core.toggles.Toggles
import com.droidective.platform.AdbPort
import com.droidective.platform.Fact
import com.droidective.platform.NetworkFacts
import com.droidective.platform.TrafficCounters
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ProxyTool(tool: ToolContext) {
    ToolScaffold("HTTP Proxy", tool) {
        ToolSection("Proxy") {
            ParamField(
                param = Param.Proxy,
                toggleId = "http-proxy",
                activeState = "on",
                label = "Host and port",
                example = "192.168.1.20:8888",
                normalise = Addresses::proxy,
                tool = tool,
            )
            ToggleRows(ToolToggles.proxy, tool)
        }
        ToolNote(
            "From an emulator, your computer is 10.0.2.2. Charles listens on 8888, Proxyman on " +
                "9090 and mitmproxy on 8080. Apps that pin certificates or ignore the system " +
                "proxy won't show up."
        )
    }
}

@Composable
fun PrivateDnsTool(tool: ToolContext) {
    ToolScaffold("Private DNS", tool) {
        ToolSection("Resolver") {
            ParamField(
                param = Param.DnsHost,
                toggleId = "private-dns",
                activeState = "hostname",
                label = "Hostname",
                example = "dns.adguard-dns.com",
                normalise = Addresses::dnsHostname,
                tool = tool,
                presets =
                    listOf(
                        "Cloudflare" to "one.one.one.one",
                        "Google" to "dns.google",
                        "AdGuard" to "dns.adguard-dns.com",
                        "Quad9" to "dns.quad9.net",
                    ),
            )
        }
        ToolSection("Mode") { ToggleRows(ToolToggles.privateDns, tool) }
        ToolNote(
            "Automatic uses DNS over TLS when the network's resolver offers it. Hostname always " +
                "uses yours, and lookups fail when it can't be reached."
        )
    }
}

/**
 * The value a parameterised toggle uses, such as the proxy's host and port. Saving while the toggle
 * is in [activeState] applies the new value straight away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamField(
    param: Param,
    toggleId: String,
    activeState: String,
    label: String,
    example: String,
    normalise: (String) -> String?,
    tool: ToolContext,
    presets: List<Pair<String, String>> = emptyList(),
) {
    val context = LocalContext.current
    val app = context.droidective
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(app.param(param)) }
    var text by rememberSaveable { mutableStateOf(saved.orEmpty()) }
    val value = normalise(text)
    val invalid = text.isNotBlank() && value == null
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = { Text(example) },
        isError = invalid,
        supportingText = {
            Text(
                when {
                    invalid -> "Not a valid ${label.lowercase()}, such as $example"
                    saved != null && value == saved -> "Saved"
                    else -> "Saved on this phone for the toggle to use"
                }
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (presets.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (name, host) ->
                AssistChip(onClick = { text = host }, label = { Text(name) })
            }
        }
    }
    Button(
        enabled = value != null && value != saved,
        onClick = {
            val newValue = value ?: return@Button
            scope.launch {
                app.setParam(param, newValue)
                saved = newValue
                val toggle = Toggles.byId(toggleId) ?: return@launch
                if (ToggleTaps.stateOf(context, toggle) == activeState) {
                    val (_, message) = runInForeground(context, toggle, activeState)
                    tool.say(message ?: "Now using $newValue")
                } else {
                    tool.say("Saved $newValue")
                }
            }
        },
    ) {
        Text("Save")
    }
}

@Composable
fun WirelessAdbTool(tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    // Android 17 asks before an app may browse the local network, which finding the port does.
    val needsLocalNetwork =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED
    var lookup by remember { mutableStateOf<Job?>(null) }
    fun look() {
        // A newer question replaces an older one; the older answer could be for the old port.
        lookup?.cancel()
        lookup = scope.launch {
            looking = true
            address = AdbPort.address(context)
            looking = false
        }
    }
    val askLocalNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { look() }
    fun lookOrAsk() {
        if (needsLocalNetwork) {
            askLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            look()
        }
    }
    LaunchedEffect(Unit) { lookOrAsk() }
    // Turning wireless debugging on from this screen (or a tile) moves adbd to a new port.
    LaunchedEffect(Unit) { Surfaces.changes.collect { look() } }
    ToolScaffold("Wireless ADB", tool) {
        ToolSection("Wireless debugging") { ToggleRows(ToolToggles.wirelessDebugging, tool) }
        ToolSection("Connect from a computer") {
            InfoRow(
                "Address",
                address ?: if (looking) "Looking…" else "Not found",
                copyable = true,
                mono = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = address != null,
                    onClick = { address?.let { context.copy("adb connect", "adb connect $it") } },
                ) {
                    Text("Copy adb connect")
                }
                OutlinedButton(enabled = !looking, onClick = ::lookOrAsk) { Text("Look again") }
            }
            ToolNote(
                "Android asks to allow wireless debugging on each new network. A computer " +
                    "pairs once first: in Wireless debugging, tap Pair device with pairing " +
                    "code. Droidective on your computer pairs and connects from its device menu."
            )
        }
        OutlinedButton(
            onClick = {
                context.openSafely(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        ) {
            Text("Open Developer options")
        }
    }
}

@Composable
fun WifiTool(tool: ToolContext) {
    val context = LocalContext.current
    var facts by remember { mutableStateOf<List<Fact>?>(null) }
    var addresses by remember { mutableStateOf<NetworkFacts.Addresses?>(null) }
    WhileVisible(2_000) {
        facts = NetworkFacts.wifi(context)
        addresses = NetworkFacts.current(context)
    }
    ToolScaffold("Wi-Fi", tool) {
        ToolSection("Wi-Fi") { ToggleRows(ToolToggles.wifi, tool) }
        ToolSection("Connection") {
            val current = facts
            if (current == null) ToolNote("Not connected to Wi-Fi.")
            else current.forEach { InfoRow(it.label, it.value) }
        }
        addresses?.let { ToolSection("Addresses") { AddressRows(it) } }
        ToolNote("The network's name needs location access, which Droidective doesn't ask for.")
    }
}

@Composable
fun GetIpTool(tool: ToolContext) {
    val context = LocalContext.current
    val say = rememberSay(tool)
    var addresses by remember { mutableStateOf(NetworkFacts.current(context)) }
    WhileVisible(2_000) { addresses = NetworkFacts.current(context) }
    ToolScaffold("Copy Device IP", tool) {
        val current = addresses
        if (current == null) {
            ToolNote("No network connection.")
            return@ToolScaffold
        }
        ToolSection("This phone") {
            Text(
                current.ipv4 ?: "No IPv4 address",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Button(
                enabled = current.ipv4 != null,
                onClick = {
                    current.ipv4?.let { context.copy("IP address", it) }
                    // Android 13 and later confirm a copy on their own.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) say("Copied")
                },
            ) {
                Text("Copy")
            }
        }
        ToolSection("Network") { AddressRows(current) }
        ToolNote("Tap any row to copy it.")
    }
}

@Composable
private fun AddressRows(addresses: NetworkFacts.Addresses) {
    InfoRow("IPv4", addresses.ipv4, copyable = true, mono = true)
    addresses.ipv6.forEach { InfoRow("IPv6", it, copyable = true, mono = true) }
    InfoRow("Gateway", addresses.gateway, copyable = true, mono = true)
    addresses.dns.forEach { InfoRow("DNS", it, copyable = true, mono = true) }
}

@Composable
fun NetworkSpeedTool(tool: ToolContext) {
    val meter = remember { RateMeter() }
    val history = remember { mutableStateListOf<Rate>() }
    var unsupported by remember { mutableStateOf(false) }
    WhileVisible(1_000) {
        val (down, up) =
            TrafficCounters.total()
                ?: run {
                    unsupported = true
                    return@WhileVisible
                }
        meter.sample(down, up, SystemClock.elapsedRealtime())?.let { rate ->
            history.add(rate)
            if (history.size > HISTORY_SECONDS) history.removeAt(0)
        }
    }
    ToolScaffold("Network Speed", tool) {
        if (unsupported) {
            ToolNote("This phone doesn't report traffic counters.")
            return@ToolScaffold
        }
        val latest = history.lastOrNull()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RateCard(
                "Down",
                latest?.downPerSecond,
                MaterialTheme.colorScheme.primary,
                Modifier.weight(1f),
            )
            RateCard(
                "Up",
                latest?.upPerSecond,
                MaterialTheme.colorScheme.tertiary,
                Modifier.weight(1f),
            )
        }
        ToolSection("Last minute") {
            SpeedChart(
                history,
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.outlineVariant,
            )
            val peak = history.maxOfOrNull { maxOf(it.downPerSecond, it.upPerSecond) }
            ToolNote(peak?.let { "Peak ${RateMeter.format(it)}" } ?: "Measuring…")
        }
        ToolNote(
            "Every app's traffic, on every network. The Network speed tile shows it in Quick " +
                "Settings."
        )
    }
}

@Composable
private fun RateCard(label: String, rate: Double?, color: Color, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = color)
            Text(
                rate?.let(RateMeter::format) ?: "—",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
private fun SpeedChart(history: List<Rate>, down: Color, up: Color, grid: Color) {
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(4.dp)) {
        val peak = history.maxOfOrNull { maxOf(it.downPerSecond, it.upPerSecond) } ?: 0.0
        val top = if (peak > 0) peak else 1.0
        val step = size.width / (HISTORY_SECONDS - 1)
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val y = size.height * fraction
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        fun line(values: List<Double>, color: Color) {
            if (values.size < 2) return
            val start = HISTORY_SECONDS - values.size
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = (start + index) * step
                val y = size.height - (value / top * size.height).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
        line(history.map { it.upPerSecond }, up)
        line(history.map { it.downPerSecond }, down)
    }
}

private const val HISTORY_SECONDS = 60
