package com.droidective.android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droidective.android.ui.copy
import com.droidective.core.net.CheckReport
import com.droidective.core.net.CheckTarget
import com.droidective.core.net.HttpAnswer
import com.droidective.core.net.NetworkProbe
import com.droidective.core.net.PinSnippets
import com.droidective.core.net.Step
import com.droidective.core.net.TlsAnswer
import com.droidective.core.net.TlsCertificate
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A host, checked from this phone the way an app's request meets it: the name, the connection, the
 * TLS handshake with the certificates and their pins, and the first HTTP answer.
 */
@Composable
fun NetworkCheckTool(tool: ToolContext) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CheckReport?>(null) }
    val target = CheckTarget.parse(address)
    fun check() {
        val now = target ?: return
        checking = true
        report = null
        scope.launch {
            report = withContext(Dispatchers.IO) { NetworkProbe.check(now) }
            checking = false
        }
    }

    ToolScaffold("Network Check", tool) {
        OutlinedTextField(
            address,
            { address = it },
            label = { Text("Host or URL") },
            placeholder = { Text("api.example.com, or http://10.0.2.2:8080/health") },
            singleLine = true,
            isError = address.isNotBlank() && target == null,
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
            keyboardActions = KeyboardActions(onGo = { check() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = target != null && !checking, onClick = ::check) {
            Text(if (checking) "Checking…" else "Check")
        }
        target?.let {
            ToolNote(
                "${if (it.tls) "TLS" else "Plain HTTP"} to ${it.host}, port ${it.port}. It goes " +
                    "straight to the host: a VPN applies, an HTTP proxy set on the phone doesn't."
            )
        }
        report?.let { Report(it) }
    }
}

@Composable
private fun Report(report: CheckReport) {
    ToolSection("Steps") {
        StepRow("Name", report.dns) { it.joinToString(", ") }
        report.tcp?.let { tcp -> StepRow("Connection", tcp) { "$it, port ${report.target.port}" } }
        report.tls?.let { tls -> StepRow("TLS", tls) { tlsSummary(it) } }
        report.http?.let { http -> StepRow("HTTP", http) { "${it.status} ${it.reason}" } }
    }
    (report.tls as? Step.Done)?.value?.let { Certificates(report.target.host, it) }
    (report.http as? Step.Done)?.value?.let { Headers(it) }
}

@Composable
private fun <T> StepRow(label: String, step: Step<T>, describe: (T) -> String) {
    when (step) {
        is Step.Done ->
            InfoRow(label, "${describe(step.value)} · ${step.millis} ms", copyable = true)
        is Step.Failed ->
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.42f),
                )
                Text(
                    "${step.reason} · ${step.millis} ms",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(0.58f),
                )
            }
    }
}

private fun tlsSummary(tls: TlsAnswer): String =
    if (tls.trusted) "${tls.protocol}, ${tls.cipher}, trusted"
    else "Not trusted: ${tls.problem ?: "unknown reason"}"

@Composable
private fun Certificates(host: String, tls: TlsAnswer) {
    val context = LocalContext.current
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val now = remember { System.currentTimeMillis() }
    ToolSection("Certificates (${tls.chain.size})") {
        tls.chain.forEachIndexed { index, cert -> CertificateRows(index, cert, dates, now) }
        val pins = tls.chain.map { it.pin }
        if (pins.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { context.copy("OkHttp pins", PinSnippets.okHttp(host, pins)) }
                ) {
                    Text("Copy for OkHttp")
                }
                TextButton(
                    onClick = {
                        context.copy("Pins", PinSnippets.networkSecurityConfig(host, pins))
                    }
                ) {
                    Text("Copy as network security config")
                }
            }
            ToolNote(
                "Pin a key you control, and keep a backup pin: a certificate renewed with a new " +
                    "key breaks every app that pinned only the old one."
            )
        }
    }
}

@Composable
private fun CertificateRows(index: Int, cert: TlsCertificate, dates: DateFormat, now: Long) {
    Text(
        if (index == 0) "The server's certificate" else "Certificate ${index + 1}, its issuer's",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
    InfoRow("Subject", cert.subject, copyable = true)
    InfoRow("Issuer", cert.issuer, copyable = true)
    if (cert.names.isNotEmpty()) InfoRow("Names", cert.names.joinToString(", "), copyable = true)
    val days = cert.daysLeft(now)
    InfoRow(
        "Expires",
        dates.format(Date(cert.notAfter)) + if (days < 0) ", expired" else ", in $days days",
    )
    InfoRow("Pin", cert.pin, copyable = true, mono = true)
    InfoRow("SHA-256", cert.sha256, copyable = true, mono = true)
}

@Composable
private fun Headers(http: HttpAnswer) {
    var all by remember(http) { mutableStateOf(http.headers.size <= SHORT) }
    ToolSection("Headers (${http.headers.size})") {
        (if (all) http.headers else http.headers.take(SHORT)).forEach { (name, value) ->
            InfoRow(name, value, copyable = true)
        }
        if (!all) TextButton(onClick = { all = true }) { Text("Show all ${http.headers.size}") }
    }
}

private const val SHORT = 12
