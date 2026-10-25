package com.droidective.android.ui.tools

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.proxy.CertificateTrust
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.copy
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.TrafficRules
import com.droidective.core.net.proxy.Unread
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The calls that weren't read, by app and reason, each with what fixes it: said once at the top of
 * the list, where someone looking for their app's response looks first. Only for apps being
 * developed, debuggable ones: another app passed through is the rules doing their job, and its own
 * row and detail still say why.
 */
@Composable
internal fun UnreadBanner(flows: List<Flow>, labels: AppLabels, onTrust: (String?) -> Unit) {
    val rules by TrafficProxy.rules.collectAsStateWithLifecycle()
    val groups =
        remember(flows, rules) {
            Unread.byApp(flows, rules)
                .filter { it.why != Unread.Unreadable && it.app?.let(labels::debuggable) == true }
                .take(MAX_GROUPS)
        }
    if (groups.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Some calls weren't read", style = MaterialTheme.typography.titleSmall)
            groups.forEach { group -> UnreadGroup(group, rules, labels, onTrust) }
        }
    }
}

@Composable
private fun UnreadGroup(
    group: Unread.ByApp,
    rules: TrafficRules,
    labels: AppLabels,
    onTrust: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = group.app?.let(labels::of) ?: "An app Droidective couldn't tell"
    val hosts = if (group.hosts.size == 1) group.hosts.single() else "${group.hosts.size} hosts"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (val why = group.why) {
                is Unread.Refused -> "$app doesn't trust Droidective's certificate ($hosts)."
                Unread.NotChosen -> "$app isn't one of the apps being read."
                is Unread.HostRule ->
                    if (why.pattern != null) "$app: ${why.pattern} is passed through by a rule."
                    else "$app: $hosts isn't a host being read."
                Unread.Unreadable -> "$app: $hosts stopped making sense as HTTP/2."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                when (val why = group.why) {
                    is Unread.Refused -> onTrust(group.app)
                    else -> scope.saveRules(context, fixed(rules, why, group.app, group.hosts))
                }
            }
        ) {
            Text(if (group.why is Unread.Refused) "Fix" else "Read it")
        }
    }
}

/** [rules] changed so what [why] kept from being read is read from now on. */
private fun fixed(rules: TrafficRules, why: Unread, app: String?, hosts: List<String>) =
    when (why) {
        // An app that can't be told is read only when every app is.
        Unread.NotChosen -> if (app != null) rules.withApp(app) else rules.copy(apps = emptyList())
        is Unread.HostRule ->
            why.pattern?.let(rules::notPassingThrough)
                ?: hosts.fold(rules) { next, host -> next.readingOnly(host) }
        is Unread.Refused,
        Unread.Unreadable -> rules
    }

/**
 * Why one call wasn't read, in words, and the fix: reading its app or host, or making the app trust
 * the certificate and reading it again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnreadSection(
    flow: Flow,
    why: Unread,
    labels: AppLabels,
    say: (String) -> Unit,
    onTrust: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rules by TrafficProxy.rules.collectAsStateWithLifecycle()
    val app = flow.app?.let(labels::of) ?: "The app"
    ToolSection("Why it wasn't read") {
        Text(headline(why, app, flow.host), style = MaterialTheme.typography.bodyLarge)
        ToolNote(explanation(why, flow.host, rules))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (why) {
                is Unread.Refused -> {
                    Button(onClick = onTrust) { Text("Show me how") }
                    OutlinedButton(
                        onClick = {
                            TrafficProxy.refusals.forgetApp(flow.uid)
                            say("Its next calls are read again")
                        }
                    ) {
                        Text("Read it again")
                    }
                }
                Unread.NotChosen,
                is Unread.HostRule ->
                    FilledTonalButton(
                        onClick = {
                            scope.saveRules(context, fixed(rules, why, flow.app, listOf(flow.host)))
                            say("Its next calls are read")
                        }
                    ) {
                        Text(
                            when {
                                why is Unread.HostRule && why.pattern != null ->
                                    "Read ${why.pattern} again"
                                why is Unread.HostRule -> "Read ${flow.host} too"
                                flow.app != null -> "Read $app too"
                                else -> "Read every app"
                            }
                        )
                    }
                Unread.Unreadable -> Unit
            }
        }
    }
}

private fun headline(why: Unread, app: String, host: String): String =
    when (why) {
        is Unread.Refused -> "$app doesn't trust Droidective's certificate"
        Unread.NotChosen -> "$app isn't one of the apps being read"
        is Unread.HostRule ->
            if (why.pattern != null) "${why.pattern} is passed through by a rule"
            else "$host isn't one of the hosts being read"
        Unread.Unreadable -> "It stopped making sense as HTTP/2"
    }

private fun explanation(why: Unread, host: String, rules: TrafficRules): String =
    when (why) {
        is Unread.Refused ->
            "An app trusts a certificate you install only when its build says so. Your own " +
                "app's debug build can; its release build stays as it is. " +
                if (rules.stepAside) {
                    "Until then its calls to $host pass through unread, so it keeps working."
                } else {
                    "Until then its calls to $host fail while the proxy runs."
                }
        Unread.NotChosen ->
            "The rules read only the apps chosen there, and pass every other app's calls " +
                "through untouched."
        is Unread.HostRule ->
            if (why.pattern != null) "Hosts passed through are never read, for any app."
            else "The rules read only the hosts listed there."
        Unread.Unreadable ->
            "The rest of that connection was passed on as it was, and the app kept working."
    }

/**
 * How to make an app being developed trust the certificate, in the order it's done: install it, add
 * the config file and the manifest line, reinstall, and read it again.
 *
 * @param uid the app to read again once it's reinstalled, when it's known.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrustSheet(uid: Int?, say: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trust by produceState<CertificateTrust?>(null) { value = TrafficProxy.trust(context) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Read your app's HTTPS", style = MaterialTheme.typography.titleLarge)
            Text(
                "Android apps don't trust a certificate you install yourself unless their own " +
                    "build says to. Your app's debug build can, in two small changes, and its " +
                    "release build stays as it is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SheetStep("1. Install Droidective's certificate")
            when (trust) {
                null -> ToolNote("Checking…")
                CertificateTrust.None -> InstallButtons(context, scope, say)
                CertificateTrust.User -> ToolNote("Done: it's installed as a user certificate.")
                CertificateTrust.System ->
                    ToolNote("Done: it's in the system store, which every app trusts.")
            }
            SheetStep("2. Add this file as res/xml/network_security_config.xml")
            ToolNote("In a React Native app that's android/app/src/main/res/xml/.")
            Snippet(DEBUG_OVERRIDES, "Network security config")
            SheetStep("3. Point to it from <application> in AndroidManifest.xml")
            Snippet(MANIFEST_LINE, "Manifest attribute")
            ToolNote(
                "Only debug builds trust it: that's what <debug-overrides> means. If your app " +
                    "has a network security config already, add the <debug-overrides> block to it."
            )
            SheetStep("4. Rebuild, install it, and read it again")
            if (uid != null) {
                Button(
                    onClick = {
                        TrafficProxy.refusals.forgetApp(uid)
                        say("Its next calls are read again")
                        onDismiss()
                    }
                ) {
                    Text("Read it again")
                }
            }
            ToolNote(
                "An app that pins its certificates refuses this one either way, and so does an app " +
                    "you can't rebuild. Chrome trusts user certificates, so it's a quick check."
            )
        }
    }
}

@Composable
private fun SheetStep(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Snippet(text: String, label: String) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { context.copy(label, text) }, Modifier.align(Alignment.End)) {
                Text("Copy")
            }
        }
    }
}

/** Saving the certificate to Downloads, and the Settings screen that installs it from there. */
@Composable
internal fun InstallButtons(context: Context, scope: CoroutineScope, say: (String) -> Unit) {
    ToolNote(
        "Android doesn't let an app install one: save it, then in Settings search for \"CA " +
            "certificate\", choose Install anyway, and pick droidective-proxy-ca.crt."
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                scope.launch {
                    val saved =
                        try {
                            TrafficProxy.saveCertificate(context)
                        } catch (e: IOException) {
                            say("It couldn't be saved: ${e.message}")
                            return@launch
                        }
                    say(
                        if (saved != null) "Saved to Downloads as droidective-proxy-ca.crt"
                        else "Android didn't make room for it in Downloads."
                    )
                }
            }
        ) {
            Text("Save certificate")
        }
        OutlinedButton(onClick = { context.openSettings(Settings.ACTION_SECURITY_SETTINGS) }) {
            Text("Open Settings")
        }
    }
}

private const val MANIFEST_LINE = "android:networkSecurityConfig=\"@xml/network_security_config\""
private const val MAX_GROUPS = 3
