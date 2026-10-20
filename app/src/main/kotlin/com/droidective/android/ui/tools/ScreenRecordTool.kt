package com.droidective.android.ui.tools

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.screenrecord.RecordRequest
import com.droidective.android.screenrecord.RecordRun
import com.droidective.android.screenrecord.ScreenRecorder
import com.droidective.android.ui.SwitchRow
import com.droidective.android.ui.openSafely
import com.droidective.core.apps.MemInfo
import com.droidective.core.device.RecordOptions
import com.droidective.core.device.ScreenRecording

/**
 * The screen, recorded by Android's own screenrecord: with taps shown, a clean status bar, or the
 * timestamps bug videos want. It keeps recording when you leave; the notification stops it.
 */
@Composable
fun ScreenRecordTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val run by ScreenRecorder.state.collectAsStateWithLifecycle()
    var half by rememberSaveable { mutableStateOf(false) }
    var mbps by rememberSaveable { mutableIntStateOf(8) }
    var minutes by rememberSaveable { mutableIntStateOf(3) }
    var delay by rememberSaveable { mutableIntStateOf(3) }
    var showTaps by rememberSaveable { mutableStateOf(true) }
    var clean by rememberSaveable { mutableStateOf(false) }
    var timestamps by rememberSaveable { mutableStateOf(false) }

    ToolScaffold("Screen Record", tool) {
        if (access.shell == null) {
            ToolNote("screenrecord runs as the shell user.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        Status(run)
        if (ScreenRecorder.busy) {
            Button(onClick = { ScreenRecorder.stop(context) }) {
                Text(if (run is RecordRun.Counting) "Cancel" else "Stop and save")
            }
        } else {
            Button(
                onClick = {
                    val size =
                        if (half) {
                            val bounds =
                                context
                                    .getSystemService(WindowManager::class.java)
                                    .maximumWindowMetrics
                                    .bounds
                            ScreenRecording.half(bounds.width(), bounds.height())
                        } else {
                            null
                        }
                    val options =
                        RecordOptions(
                            bitRateMbps = mbps,
                            width = size?.first,
                            height = size?.second,
                            timestamps = timestamps,
                            limitSeconds = minutes * SECONDS_PER_MINUTE,
                        )
                    ScreenRecorder.start(
                        context,
                        RecordRequest(options, showTaps, clean, delaySeconds = delay),
                    )
                }
            ) {
                Text("Start recording")
            }
        }
        ToolSection("Recording") {
            Choices("Size", listOf(false to "Full", true to "Half"), half) { half = it }
            Choices("Quality", listOf(4 to "4 Mbps", 8 to "8 Mbps", 20 to "20 Mbps"), mbps) {
                mbps = it
            }
            Choices("Stops after", listOf(3 to "3 min", 10 to "10 min", 30 to "30 min"), minutes) {
                minutes = it
            }
            Choices("Starts after", listOf(0 to "Now", 3 to "3 s", 10 to "10 s"), delay) {
                delay = it
            }
        }
        ToolSection("While it records") {
            SwitchRow("Show taps", checked = showTaps) { showTaps = it }
            SwitchRow(
                "Clean status bar",
                checked = clean,
                note = "Demo mode: a full battery and signal, a fixed clock, no notifications.",
            ) {
                clean = it
            }
            SwitchRow(
                "Timestamps",
                checked = timestamps,
                note = "A clock and frame counter burned into the video, for bug reports.",
            ) {
                timestamps = it
            }
            ToolNote("Taps and the status bar go back the way they were when it stops.")
        }
    }
}

@Composable
private fun Status(run: RecordRun?) {
    val context = LocalContext.current
    when (run) {
        null -> ToolNote("It saves to Movies/Droidective. Stop it from the notification.")
        is RecordRun.Counting -> ToolNote("Starting in ${run.left} s: open what you want to show.")
        is RecordRun.Recording -> ToolNote("Recording. Stop it here or from the notification.")
        RecordRun.Saving -> ToolNote("Saving…")
        is RecordRun.Failed -> ToolNote(run.message)
        is RecordRun.Saved ->
            ToolSection("Saved") {
                InfoRow("File", "Movies/Droidective/${run.name}", copyable = true, mono = true)
                InfoRow("Length", "${run.seconds / 60}:%02d".format(run.seconds % 60))
                InfoRow("Size", MemInfo.size(run.bytes / BYTES_PER_KB))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { context.openSafely(ScreenRecorder.share(run)) }) {
                        Text("Share")
                    }
                    OutlinedButton(onClick = { context.openSafely(ScreenRecorder.play(run)) }) {
                        Text("Play")
                    }
                }
            }
    }
}

@Composable
private fun <T> Choices(
    label: String,
    options: List<Pair<T, String>>,
    chosen: T,
    onChoose: (T) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, title) ->
            FilterChip(
                selected = value == chosen,
                onClick = { onChoose(value) },
                label = { Text(title) },
            )
        }
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val BYTES_PER_KB = 1024L
