package com.droidective.android.ui.tools

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.proxy.CertificateTrust
import com.droidective.android.proxy.ProxyRun
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.Symbols
import com.droidective.core.AccessLevel
import com.droidective.core.net.proxy.FlowLabels
import com.droidective.core.net.proxy.Throttle
import kotlinx.coroutines.launch

/**
 * The proxy in a word or two, for the header: off, recording, listening without the phone pointed
 * here, or why it couldn't start; with the network speed when it's slowed. A tap opens the rest.
 */
@Composable
internal fun TrafficStatusChip(onClick: () -> Unit) {
    val run by TrafficProxy.state.collectAsStateWithLifecycle()
    val pointing by TrafficProxy.pointing.collectAsStateWithLifecycle()
    val throttle by TrafficProxy.throttle.collectAsStateWithLifecycle()
    val slowed = Throttle.presets.firstOrNull { it.throttle == throttle && !throttle.isOff }
    val (label, dot) =
        when {
            run is ProxyRun.Failed -> "Couldn't start" to MaterialTheme.colorScheme.error
            run !is ProxyRun.Listening && pointing ->
                "Phone offline" to MaterialTheme.colorScheme.error
            run is ProxyRun.Starting -> "Starting…" to MaterialTheme.colorScheme.tertiary
            run !is ProxyRun.Listening -> "Off" to MaterialTheme.colorScheme.outline
            pointing -> "Recording" to MaterialTheme.colorScheme.primary
            else -> "Not pointed here" to MaterialTheme.colorScheme.tertiary
        }
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                listOfNotNull(label, slowed?.name).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = { Box(Modifier.size(DOT).background(dot, CircleShape)) },
        trailingIcon = {
            Icon(
                painterResource(Symbols.byName.getValue("expand_more").outlined),
                contentDescription = "Proxy",
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = Modifier.widthIn(max = CHIP_MAX),
    )
}

/** Start and stop, whether the phone sends its traffic here, and how fast a network to play. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProxySheet(tool: ToolContext, onDismiss: () -> Unit) {
    val run by TrafficProxy.state.collectAsStateWithLifecycle()
    val pointing by TrafficProxy.pointing.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Proxy", style = MaterialTheme.typography.titleLarge)
            ProxyControls()
            if (run !is ProxyRun.Listening && pointing) StrandedNote(tool)
            if (run is ProxyRun.Listening) {
                Pointing(tool)
                SpeedRow()
            } else {
                ToolNote(
                    "Start points the phone here. While it does, an app that doesn't trust the " +
                        "certificate can't reach its HTTPS hosts. Stop puts the phone's proxy " +
                        "setting back the way it was."
                )
            }
        }
    }
}

/** Off, starting, listening or failed, and the button that starts or stops it. */
@Composable
private fun ProxyControls() {
    val context = LocalContext.current
    val run by TrafficProxy.state.collectAsStateWithLifecycle()
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
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
            modifier = Modifier.weight(1f),
        )
        if (run is ProxyRun.Listening) {
            OutlinedButton(onClick = { TrafficProxy.stop(context) }) { Text("Stop") }
        } else {
            Button(
                onClick = {
                    TrafficProxy.start(context, point = access.allows(AccessLevel.Granted))
                },
                enabled = run !is ProxyRun.Starting,
            ) {
                Text("Start")
            }
        }
    }
}

/** The switch that points the phone here, or with no Granted, how to point Wi-Fi by hand. */
@Composable
private fun Pointing(tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pointing by TrafficProxy.pointing.collectAsStateWithLifecycle()
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        TrafficProxy.refreshPointing(context)
        onPauseOrDispose {}
    }
    if (!access.allows(AccessLevel.Granted)) {
        ManualPointing(tool)
        return
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable {
                scope.launch {
                    if (!TrafficProxy.point(context, !pointing)) {
                        tool.say("The phone's proxy setting didn't change.")
                    }
                }
            }
            .padding(vertical = 4.dp),
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
        Switch(
            checked = pointing,
            onCheckedChange = { here ->
                scope.launch {
                    if (!TrafficProxy.point(context, here)) {
                        tool.say("The phone's proxy setting didn't change.")
                    }
                }
            },
        )
    }
}

/** How slow a network to play, for seeing an app's loading states. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedRow() {
    val throttle by TrafficProxy.throttle.collectAsStateWithLifecycle()
    Text("Network speed", style = MaterialTheme.typography.labelLarge)
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

/**
 * What the Requests tab is with nothing in it: how to watch an app's API calls, in three steps,
 * each ticked once it's done, with the button that does it.
 */
@Composable
internal fun WatchCard(trust: CertificateTrust?, onCertificate: () -> Unit, onTrust: () -> Unit) {
    val context = LocalContext.current
    val run by TrafficProxy.state.collectAsStateWithLifecycle()
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val installed = trust == CertificateTrust.User || trust == CertificateTrust.System
    val listening = run is ProxyRun.Listening
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Watch your app's API calls", style = MaterialTheme.typography.titleLarge)
            Text(
                "Every request your app makes and what comes back, read here on the phone, " +
                    "with no computer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Step(1, "Install Droidective's certificate.", done = installed) {
                if (!installed) TextButton(onClick = onCertificate) { Text("Set it up") }
            }
            Step(2, "Let your app's debug build trust it.", done = false) {
                TextButton(onClick = onTrust) { Text("Show me how") }
            }
            Step(3, "Start, then use your app.", done = listening) {
                if (!listening) {
                    Button(
                        onClick = {
                            TrafficProxy.start(context, point = access.allows(AccessLevel.Granted))
                        },
                        enabled = run !is ProxyRun.Starting,
                    ) {
                        Text("Start")
                    }
                }
            }
            if (listening) ToolNote("Listening. Its requests show here as it makes them.")
        }
    }
}

@Composable
private fun Step(number: Int, text: String, done: Boolean, action: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(STEP)
                .background(
                    if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondaryContainer,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    painterResource(Symbols.byName.getValue("check_circle").outlined),
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        action()
    }
}

private val DOT = 8.dp
private val STEP = 28.dp
private val CHIP_MAX = 200.dp
