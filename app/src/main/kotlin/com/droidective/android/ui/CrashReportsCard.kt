package com.droidective.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The switch for crash reports to Sentry, and what one carries. */
@Composable
fun CrashReportsCard(on: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Crash reports", style = MaterialTheme.typography.titleSmall)
            SwitchRow(
                "Send crash reports",
                checked = on,
                note =
                    "When Droidective crashes or freezes, its stack trace goes to the developer " +
                        "through Sentry, with the phone's model and Android version. Package " +
                        "names, file paths, IP addresses and URLs are taken out on the phone " +
                        "first, and nothing on screen is captured.",
                onChange = onChange,
            )
        }
    }
}
