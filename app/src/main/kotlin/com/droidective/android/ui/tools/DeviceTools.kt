package com.droidective.android.ui.tools

import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.ui.copy
import com.droidective.core.device.RootCheck
import com.droidective.core.device.RootEvidence
import com.droidective.core.device.RootVerdict
import com.droidective.platform.DeviceFacts
import com.droidective.platform.FactGroup
import com.droidective.platform.RootProbe
import com.droidective.platform.asText

@Composable
fun DeviceInfoTool(tool: ToolContext) {
    val context = LocalContext.current
    val say = rememberSay(tool)
    var groups by remember { mutableStateOf<List<FactGroup>?>(null) }
    LaunchedEffect(Unit) {
        val app = context.droidective
        app.access.settled()
        groups = DeviceFacts.collect(context, app.access.shell())
    }
    ToolScaffold("Device Info", tool) {
        val current = groups
        if (current == null) {
            ToolNote("Reading…")
            return@ToolScaffold
        }
        OutlinedButton(
            onClick = {
                context.copy("Device info", current.asText())
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) say("Copied")
            }
        ) {
            Text("Copy all")
        }
        current.forEach { group ->
            ToolSection(group.title) {
                group.facts.forEach { InfoRow(it.label, it.value, copyable = true) }
            }
        }
        ToolNote("Tap any row to copy it.")
    }
}

@Composable
fun RootStatusTool(tool: ToolContext) {
    val context = LocalContext.current
    val app = context.droidective
    val access by app.access.access.collectAsStateWithLifecycle()
    var evidence by remember { mutableStateOf<RootEvidence?>(null) }
    LaunchedEffect(Unit) {
        app.access.settled()
        evidence = RootProbe.evidence(context, app.access.shell())
    }
    ToolScaffold("Root Status", tool) {
        val current = evidence
        if (current == null) {
            ToolNote("Looking…")
            return@ToolScaffold
        }
        val verdict = RootCheck.verdict(current)
        ToolSection("Verdict") {
            Text(
                verdict.title,
                style = MaterialTheme.typography.headlineSmall,
                color =
                    if (verdict == RootVerdict.NotRooted) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            val reasons = RootCheck.reasons(current)
            if (reasons.isEmpty()) ToolNote("No su, no root manager, and verified boot is intact.")
            reasons.forEach { InfoRow("Found", it) }
        }
        ToolSection("Droidective") {
            InfoRow(
                "Has root",
                when {
                    access.shell?.isRoot == true -> "Yes, through ${access.shell?.title}"
                    verdict != RootVerdict.Rooted -> "No"
                    !app.access.rootAllowed -> "Not yet: turn on Use root in Setup"
                    else -> "Not yet: allow Droidective in your root manager"
                },
            )
        }
        ToolSection("Signals") {
            InfoRow("Verified boot", current.verifiedBootState)
            InfoRow("Build tags", current.buildTags)
            InfoRow("Debuggable", current.debuggable?.let { if (it) "Yes" else "No" })
        }
        ToolNote(
            "Checked without asking for root: su on the usual paths, root manager apps, and " +
                "what the bootloader reports. Root that hides itself won't show here."
        )
    }
}
