package com.droidective.android.ui.automations

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.droidective.android.ui.tools.ToolNote
import com.droidective.android.ui.tools.ToolSection
import com.droidective.core.automations.Action
import com.droidective.core.automations.Automation
import com.droidective.core.automations.Button
import com.droidective.core.automations.Condition
import com.droidective.core.automations.Event
import com.droidective.core.automations.State
import com.droidective.core.automations.Trigger

/** Ready-made automations from the design, added switched off. */
internal val examples: List<Automation> =
    listOf(
        Automation(
            "bedtime",
            "Bedtime",
            enabled = false,
            trigger = Trigger.On(Event.Schedule(23 * 60 + 30)),
            actions =
                listOf(
                    Action.SetToggle("do-not-disturb", "on"),
                    Action.SetToggle("battery-saver", "on"),
                ),
        ),
        Automation(
            "after-a-restart",
            "After a restart",
            enabled = false,
            trigger = Trigger.On(Event.Restarted),
            conditions = listOf(Condition(State.ShellAvailable, negate = true)),
            actions =
                listOf(
                    Action.Notify(
                        "Start Shizuku",
                        "Shell stopped when the phone restarted.",
                        listOf(
                            Button(
                                "Open Shizuku",
                                Action.OpenApp("moe.shizuku.privileged.api", "Shizuku"),
                            )
                        ),
                    )
                ),
        ),
    )

@Composable
internal fun Examples(onAdd: (Automation) -> Unit) {
    ToolSection("Start from an example") {
        examples.forEach { example ->
            Text(example.name, style = MaterialTheme.typography.titleSmall)
            ToolNote(example.trigger.describe())
            TextButton(onClick = { onAdd(example) }) { Text("Add it, switched off") }
        }
    }
}
