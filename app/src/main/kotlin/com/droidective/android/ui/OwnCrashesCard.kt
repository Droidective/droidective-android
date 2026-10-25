package com.droidective.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidective.android.crashes.OwnCrashes
import com.droidective.core.ago

/**
 * Shown only after Droidective itself crashed: the reports, ready for a bug report. [reporting] is
 * whether crash reports go to Sentry, which changes what this card can promise.
 */
@Composable
fun OwnCrashesCard(crashes: OwnCrashes, reporting: Boolean) {
    val context = LocalContext.current
    var recent by remember { mutableStateOf(crashes.recent()) }
    val last = recent.firstOrNull() ?: return
    val now = System.currentTimeMillis()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Droidective crashed", style = MaterialTheme.typography.titleSmall)
            Text(
                if (recent.size == 1) "It crashed ${ago(last.timeMillis, now)}."
                else "It crashed ${recent.size} times; the last was ${ago(last.timeMillis, now)}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (reporting) {
                    "Copy it into a bug report. This full report stays on the phone; the crash " +
                        "report Droidective sent left out names, paths and addresses."
                } else {
                    "Copy it into a bug report. Nothing leaves this phone unless you paste it " +
                        "somewhere."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        context.copy(
                            "Droidective crash report",
                            recent.joinToString("\n\n----\n\n") { it.text },
                        )
                    }
                ) {
                    Text(if (recent.size == 1) "Copy report" else "Copy reports")
                }
                OutlinedButton(
                    onClick = {
                        crashes.clear()
                        recent = emptyList()
                    }
                ) {
                    Text("Clear")
                }
            }
        }
    }
}
