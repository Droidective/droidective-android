package com.droidective.android.ui.tools

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidective.android.ui.SwitchRow
import com.droidective.android.ui.openSafely
import com.droidective.core.toggles.FakeBattery
import kotlin.math.roundToInt

@Composable
fun DeveloperSettingsTool(tool: ToolContext) {
    val context = LocalContext.current
    ToolScaffold("Developer Settings", tool) {
        ToolSection("Drawing") { ToggleRows(ToolToggles.drawing, tool) }
        ToolSection("Input") { ToggleRows(ToolToggles.input, tool) }
        ToolSection("Apps") { ToggleRows(ToolToggles.appBehaviour, tool) }
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
fun SimulateTool(tool: ToolContext) {
    val shell = rememberToolShell(tool)
    var level by rememberSaveable { mutableFloatStateOf(15f) }
    var unplugged by rememberSaveable { mutableStateOf(true) }
    ToolScaffold("Simulate", tool) {
        ToolSection("Battery") {
            Text(
                "${level.roundToInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Slider(
                value = level,
                onValueChange = { level = it },
                valueRange = 0f..100f,
                steps = 19,
            )
            SwitchRow(
                "Unplugged",
                checked = unplugged,
                onChange = { unplugged = it },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val percent = level.roundToInt()
                        shell.run(
                            "Fake battery",
                            FakeBattery.set(percent, unplugged),
                            "Battery shows $percent%",
                        )
                    }
                ) {
                    Text("Apply")
                }
                OutlinedButton(
                    onClick = {
                        shell.run(
                            "Reset battery",
                            FakeBattery.reset,
                            "Battery shows the real level",
                        )
                    }
                ) {
                    Text("Reset")
                }
            }
            ToolNote("Needs Shell. Android keeps the fake level until you reset it or restart.")
        }
        ToolSection("Display") { ToggleRows(ToolToggles.display, tool) }
        ToolSection("Network") { ToggleRows(ToolToggles.network, tool) }
        ToolSection("Power") { ToggleRows(ToolToggles.power, tool) }
    }
}

@Composable
fun DemoModeTool(tool: ToolContext) {
    ToolScaffold("Demo Mode", tool) {
        ToolSection("Status bar") { ToggleRows(ToolToggles.demoMode, tool) }
        ToolNote(
            "Demo mode shows 09:41, a full battery and full Wi-Fi, and hides notification icons, " +
                "so screenshots match from one run to the next. Droidective on your computer " +
                "sets the same status bar, and there's a Quick Settings tile for it."
        )
    }
}

@Composable
fun RestrictionsTool(tool: ToolContext) {
    ToolScaffold("System Restrictions", tool) {
        ToolSection("Installs") { ToggleRows(ToolToggles.installs, tool) }
        ToolSection("Runtime") { ToggleRows(ToolToggles.runtime, tool) }
        ToolNote("SELinux needs root, and Android puts it back to enforcing when it restarts.")
    }
}
