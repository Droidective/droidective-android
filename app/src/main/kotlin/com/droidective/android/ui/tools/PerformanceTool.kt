package com.droidective.android.ui.tools

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.ui.automations.AppChooser
import com.droidective.core.apps.MemInfo
import com.droidective.core.device.FrameStats
import com.droidective.core.device.Performance
import com.droidective.core.device.PerformanceReading
import com.droidective.core.device.PerformanceTracker
import com.droidective.core.device.Thermal
import com.droidective.core.device.TopProcess
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's load, live, and one app's frames: how many it drew since the last reset and how long
 * they took, the numbers Android Studio's profilers and Play's vitals judge it by.
 */
@Composable
fun PerformanceTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val shell = rememberToolShell(tool)
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var showTop by rememberSaveable { mutableStateOf(false) }
    var top by remember { mutableStateOf<List<TopProcess>?>(null) }
    var reading by remember { mutableStateOf<PerformanceReading?>(null) }
    val tracker = remember { PerformanceTracker() }
    val cpuHistory = remember { mutableStateListOf<Double>() }
    val fpsHistory = remember { mutableStateListOf<Double>() }

    WhileVisible(TICK_MILLIS) {
        if (access.shell == null) return@WhileVisible
        val output = shellOutput(context, Performance.sample(packageName)) ?: return@WhileVisible
        val sample = withContext(Dispatchers.Default) { Performance.parse(output) }
        val next = tracker.next(sample, SystemClock.elapsedRealtime())
        reading = next
        next.cpuPercent?.let { cpuHistory.keepLast(it) }
        next.fps?.let { fpsHistory.keepLast(it) }
    }
    if (showTop && access.shell != null) {
        WhileVisible(TOP_MILLIS) {
            top = shellOutput(context, Performance.top)?.let(Performance::topProcesses)
        }
    }

    ToolScaffold("Performance Monitor", tool) {
        if (access.shell == null) {
            ToolNote("The phone's counters are read as the shell user.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        val now = reading
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("CPU", now?.cpuPercent?.let(::percent), Modifier.weight(1f))
            StatCard(
                "Memory",
                now?.memUsedKb?.let { used ->
                    now.memTotalKb?.let { "${MemInfo.size(used)} used" }
                },
                Modifier.weight(1f),
            )
        }
        ToolSection("CPU, last minute") {
            HistoryChart(cpuHistory, top = 100.0, MaterialTheme.colorScheme.primary)
            now?.cores
                ?.filter { it.core >= 0 }
                ?.forEach { core ->
                    Bar("Core ${core.core}", core.percent / 100, percent(core.percent))
                }
        }
        now?.memTotalKb?.let { total ->
            ToolSection("Memory") {
                val used = now.memUsedKb ?: 0
                Bar(
                    "Used",
                    used.toDouble() / total,
                    "${MemInfo.size(used)} of ${MemInfo.size(total)}",
                )
                ToolNote("Used is what Android couldn't hand to an app without closing something.")
            }
        }
        now?.thermal?.let { ThermalSection(it) }
        AppSection(
            packageName,
            now,
            fpsHistory,
            onPick = { choosing = true },
            onReset = { pkg ->
                shell.run("Reset", listOf(Performance.resetFrames(pkg)), done = "Counting from now")
            },
        )
        TopSection(showTop, top, onShow = { showTop = it })
    }

    if (choosing) {
        AppChooser("Which app", onDismiss = { choosing = false }) {
            packageName = it.packageName
            tracker.reset()
            fpsHistory.clear()
            reading = null
            choosing = false
        }
    }
}

@Composable
private fun StatCard(label: String, value: String?, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(value ?: "—", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun Bar(label: String, fraction: Double, value: String) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { fraction.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThermalSection(thermal: Thermal) {
    ToolSection("Temperature") {
        thermal.statusTitle?.let { InfoRow("Throttling", if (thermal.status == 0) "None" else it) }
        listOf(
                "CPU" to thermal.cpu,
                "GPU" to thermal.gpu,
                "Battery" to thermal.battery,
                "Skin" to thermal.skin,
            )
            .filter { it.second != null }
            .forEach { (label, celsius) -> InfoRow(label, celsius?.let(::degrees)) }
        if (thermal.temperatures.isEmpty()) ToolNote("This phone's thermal HAL reports nothing.")
    }
}

@Composable
private fun AppSection(
    packageName: String?,
    reading: PerformanceReading?,
    fpsHistory: List<Double>,
    onPick: () -> Unit,
    onReset: (String) -> Unit,
) {
    val context = LocalContext.current
    ToolSection("App") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(packageName?.let { CrashAlerts.label(context, it) } ?: "No app picked")
                Text(
                    packageName ?: "Its frames, and its share of the CPU",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (packageName != null) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onPick) {
                Text(if (packageName == null) "Pick an app" else "Change")
            }
        }
        if (packageName == null) return@ToolSection
        val frames = reading?.frames
        when {
            reading == null -> ToolNote("Reading…")
            frames == null -> ToolNote("It isn't running. Open it, and what it draws shows here.")
            else -> {
                InfoRow("Drawing now", reading.fps?.let { "%.0f fps".format(Locale.ROOT, it) })
                InfoRow("CPU, of all cores", reading.appCpuPercent?.let(::percent))
                HistoryChart(
                    fpsHistory,
                    top = maxOf(60.0, fpsHistory.maxOrNull() ?: 0.0),
                    MaterialTheme.colorScheme.tertiary,
                )
                FrameReport(frames)
                TextButton(onClick = { onReset(packageName) }) { Text("Reset") }
                ToolNote(
                    "Reset, use the app, then come back: the frame times describe what you did. " +
                        "In split screen, it draws live beside this."
                )
            }
        }
    }
}

@Composable
private fun FrameReport(frames: FrameStats) {
    val jank = if (frames.total > 0) frames.janky * 100.0 / frames.total else null
    InfoRow(
        "Since reset",
        "${frames.total} frames" + (jank?.let { ", ${percent(it)} janky" } ?: ""),
    )
    frames.percentiles.forEach { (rank, ms) -> InfoRow("${rank}th percentile", "$ms ms") }
    frames.slowUiThread?.let { InfoRow("Slow UI thread", "$it") }
    frames.slowDraw?.let { InfoRow("Slow draw commands", "$it") }
    frames.missedVsync?.let { InfoRow("Missed vsync", "$it") }
    frames.highInputLatency?.let { InfoRow("High input latency", "$it") }
}

@Composable
private fun TopSection(shown: Boolean, top: List<TopProcess>?, onShow: (Boolean) -> Unit) {
    ToolSection("Busiest processes") {
        if (!shown) {
            TextButton(onClick = { onShow(true) }) { Text("Show") }
            ToolNote("What's using the CPU, everything included, read with top.")
            return@ToolSection
        }
        val busy = top?.filter { it.cpu > 0 }
        when {
            busy == null -> ToolNote("Reading…")
            busy.isEmpty() -> ToolNote("Nothing used the CPU in the last second.")
            else -> busy.forEach { ProcessRow(it) }
        }
        ToolNote("A percent is of one core, the way top counts it.")
        TextButton(onClick = { onShow(false) }) { Text("Hide") }
    }
}

@Composable
private fun ProcessRow(process: TopProcess) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            process.name,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(percent(process.cpu), style = MaterialTheme.typography.bodySmall)
        Text(
            process.resident,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryChart(history: List<Double>, top: Double, color: Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(4.dp)) {
        val step = size.width / (HISTORY - 1)
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val y = size.height * fraction
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (history.size < 2) return@Canvas
        val start = HISTORY - history.size
        val path = Path()
        history.forEachIndexed { index, value ->
            val x = (start + index) * step
            val y = size.height - (value / top * size.height).toFloat().coerceIn(0f, size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun MutableList<Double>.keepLast(value: Double) {
    add(value)
    if (size > HISTORY) removeAt(0)
}

private fun percent(value: Double): String = "%.0f%%".format(Locale.ROOT, value)

private fun degrees(celsius: Double): String = "%.1f °C".format(Locale.ROOT, celsius)

private const val TICK_MILLIS = 1_000L
private const val TOP_MILLIS = 2_000L
private const val HISTORY = 60
