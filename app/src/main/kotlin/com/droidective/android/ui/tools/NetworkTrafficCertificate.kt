package com.droidective.android.ui.tools

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.droidective.android.proxy.CertificateTrust
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.ui.copy
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Whether the phone trusts the certificate, asked again on every resume, since it's installed in
 * Settings. A change clears the refusals: what an app refused under the old answer may not be
 * refused now. Null until the first answer.
 */
@Composable
internal fun rememberTrust(): CertificateTrust? {
    val context = LocalContext.current
    var trust by rememberSaveable { mutableStateOf<CertificateTrust?>(null) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    LaunchedEffect(resumes) {
        val now = TrafficProxy.trust(context)
        if (trust != null && trust != now) TrafficProxy.refusals.clear()
        trust = now
    }
    return trust
}

/** The Certificate tab: whether it's installed, how to install it, and how your app trusts it. */
@Composable
internal fun CertificateTab(trust: CertificateTrust?, say: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    TabColumn {
        ToolSection("Certificate") {
            Text(
                when (trust) {
                    null -> "Checking…"
                    CertificateTrust.None -> "Not installed: HTTPS can't be read"
                    CertificateTrust.User -> "Installed as a user certificate"
                    CertificateTrust.System -> "Installed in the system store"
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            when (trust) {
                CertificateTrust.None -> InstallButtons(context, scope, say)
                CertificateTrust.System ->
                    ToolNote("Every app that doesn't pin its certificates can be read.")
                else -> Unit
            }
        }
        ToolSection("Your app") {
            ToolNote(TRUST_INTRO)
            TrustSteps(uid = null, first = 1, say, onRead = {})
        }
    }
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
    val trust = rememberTrust()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Read your app's HTTPS", style = MaterialTheme.typography.titleLarge)
            Text(
                TRUST_INTRO,
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
            TrustSteps(uid, first = 2, say, onRead = onDismiss)
        }
    }
}

/**
 * The changes to the app itself, then reading it again: the app with [uid] when it's known, every
 * app that refused when it isn't. Numbered from [first], after any step shown before them.
 */
@Composable
private fun ColumnScope.TrustSteps(
    uid: Int?,
    first: Int,
    say: (String) -> Unit,
    onRead: () -> Unit,
) {
    SheetStep("$first. Add this file as res/xml/network_security_config.xml")
    ToolNote("In a React Native app that's android/app/src/main/res/xml/.")
    Snippet(DEBUG_OVERRIDES, "Network security config")
    SheetStep("${first + 1}. Point to it from <application> in AndroidManifest.xml")
    Snippet(MANIFEST_LINE, "Manifest attribute")
    ToolNote(
        "Only debug builds trust it: that's what <debug-overrides> means. If your app has a " +
            "network security config already, add the <debug-overrides> block to it."
    )
    SheetStep("${first + 2}. Rebuild, install it, and read it again")
    Button(
        onClick = {
            if (uid != null) TrafficProxy.refusals.forgetApp(uid) else TrafficProxy.refusals.clear()
            say(if (uid != null) "Its next calls are read again" else "Every app is read again")
            onRead()
        }
    ) {
        Text("Read it again")
    }
    ToolNote(
        "An app that pins its certificates refuses this one either way, and so does an app you " +
            "can't rebuild. Chrome trusts user certificates, so it's a quick check."
    )
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

private const val TRUST_INTRO =
    "Android apps don't trust a certificate you install yourself unless their own build says to. " +
        "Your app's debug build can, in two small changes, and its release build stays as it is."
private const val MANIFEST_LINE = "android:networkSecurityConfig=\"@xml/network_security_config\""
