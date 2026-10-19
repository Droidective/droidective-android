package com.droidective.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidective.android.BuildConfig
import com.droidective.android.update.UpdateStatus
import com.droidective.android.update.Updates
import kotlinx.coroutines.launch

/** This version, whether a newer one is on GitHub, and the daily check's switch. */
@Composable
fun UpdatesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(Updates.status(context)) }
    var automatic by remember { mutableStateOf(Updates.automatic(context)) }
    var checking by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleSmall)
            Text("This is Droidective ${BuildConfig.VERSION_NAME}.")
            Text(
                describe(status),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (status is UpdateStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                (status as? UpdateStatus.Available)?.let { available ->
                    Button(onClick = { context.openSafely(Updates.releasePage(available.page)) }) {
                        Text("Open release")
                    }
                }
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            status = Updates.check(context, fromBackground = false)
                            checking = false
                        }
                    },
                ) {
                    Text("Check now")
                }
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check once a day")
                    Text(
                        "Asks GitHub for the latest release. The request carries only this " +
                            "app’s version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = automatic,
                    onCheckedChange = {
                        automatic = it
                        Updates.setAutomatic(context, it)
                    },
                )
            }
        }
    }
}

private fun describe(status: UpdateStatus): String =
    when (status) {
        UpdateStatus.NeverChecked -> "Not checked yet."
        is UpdateStatus.UpToDate -> "Up to date. Checked ${ago(status.at)}."
        is UpdateStatus.NoRelease -> "No release on GitHub yet. Checked ${ago(status.at)}."
        is UpdateStatus.Available -> "Version ${status.version} is available."
        is UpdateStatus.Failed -> "The last check failed: ${status.reason}"
    }

/** "just now", "5 minutes ago", "3 hours ago", "2 days ago": always lower case mid-sentence. */
private fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = now - at
    return when {
        elapsed < DateUtils.MINUTE_IN_MILLIS -> "just now"
        elapsed < DateUtils.DAY_IN_MILLIS ->
            DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
        else ->
            (elapsed / DateUtils.DAY_IN_MILLIS).let {
                if (it == 1L) "a day ago" else "$it days ago"
            }
    }
}
