package com.droidective.android.ui.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.ui.copy
import com.droidective.android.uiinspect.UiCapture
import com.droidective.android.uiinspect.UiCaptures
import com.droidective.core.device.Bounds
import com.droidective.core.device.UiHierarchy
import com.droidective.core.device.UiNode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Any app's views, from `uiautomator dump`: what each one is, its id and text, and the line a UI
 * test would find it by. It points out clickable views TalkBack can't name and targets too small to
 * tap.
 */
@Composable
fun UiInspectorTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val capture by UiCaptures.state.collectAsStateWithLifecycle()
    var delay by rememberSaveable { mutableIntStateOf(3) }

    ToolScaffold("UI Inspector", tool) {
        if (access.shell == null) {
            ToolNote("uiautomator runs as the shell user.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(3, 10).forEach { seconds ->
                FilterChip(
                    selected = delay == seconds,
                    onClick = { delay = seconds },
                    label = { Text("In $seconds s") },
                )
            }
            Button(enabled = !UiCaptures.busy, onClick = { UiCaptures.capture(context, delay) }) {
                Text("Capture")
            }
        }
        when (val now = capture) {
            null ->
                ToolNote(
                    "Capture, then open the app to inspect before the countdown ends. Its views " +
                        "show here when you come back."
                )
            is UiCapture.Counting -> ToolNote("Open the app now: capturing in ${now.left} s.")
            UiCapture.Capturing -> ToolNote("Capturing…")
            is UiCapture.Failed -> ToolNote(now.message)
            is UiCapture.Captured -> Inspection(now)
        }
    }
}

private enum class Show(val title: String) {
    All("All"),
    Clickable("Clickable"),
    Issues("Issues"),
}

@Composable
private fun Inspection(capture: UiCapture.Captured) {
    val context = LocalContext.current
    val nodes = capture.nodes
    val minPx = with(LocalDensity.current) { MIN_TARGET_DP.dp.roundToPx() }
    val small = remember(nodes, minPx) { UiHierarchy.smallTargets(nodes, minPx).toSet() }
    val unlabelled = remember(nodes) { UiHierarchy.unlabelled(nodes).toSet() }
    var selected by remember(capture) { mutableStateOf<Int?>(null) }
    var show by rememberSaveable { mutableStateOf(Show.All) }
    var search by rememberSaveable { mutableStateOf("") }

    ToolSection(capture.app?.let { CrashAlerts.label(context, it) } ?: "Captured") {
        InfoRow("Views", "${nodes.size}")
        InfoRow("Unlabelled buttons", "${unlabelled.size}")
        InfoRow("Under 48 dp", "${small.size}")
        if (unlabelled.isNotEmpty() || small.isNotEmpty()) {
            ToolNote(
                "Unlabelled: clickable, with no text or description for TalkBack to read. Under " +
                    "48 dp: smaller than Android's minimum touch target."
            )
        }
    }
    capture.screenshot?.let { file ->
        Screenshot(
            file,
            nodes,
            selected,
            flagged = if (show == Show.Issues) unlabelled + small else emptySet(),
        ) {
            selected = it
        }
    }
    selected?.let {
        Details(
            nodes[it],
            nodes,
            issues =
                listOfNotNull(
                    "unlabelled".takeIf { _ -> it in unlabelled },
                    "under 48 dp".takeIf { _ -> it in small },
                ),
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Show.entries.forEach { option ->
            FilterChip(
                selected = show == option,
                onClick = { show = option },
                label = { Text(option.title) },
            )
        }
    }
    OutlinedTextField(
        search,
        { search = it },
        placeholder = { Text("Find text, description or id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val term = search.trim()
    val shown =
        nodes.indices.filter { index ->
            val node = nodes[index]
            val kept =
                when (show) {
                    Show.All -> true
                    Show.Clickable -> node.clickable || node.longClickable
                    Show.Issues -> index in unlabelled || index in small
                }
            kept &&
                (term.isEmpty() ||
                    listOf(node.text, node.contentDescription, node.resourceId).any {
                        it.contains(term, ignoreCase = true)
                    })
        }
    ToolSection("Views (${shown.size})") {
        var limit by remember(shown) { mutableIntStateOf(PAGE) }
        shown.take(limit).forEach { index ->
            NodeRow(nodes[index], selected = index == selected) { selected = index }
        }
        if (shown.isEmpty()) ToolNote("Nothing matches that.")
        if (shown.size > limit) {
            TextButton(onClick = { limit += PAGE }) {
                Text("Show more (${shown.size - limit} left)")
            }
        }
    }
}

/** The screenshot, with the chosen view outlined; a tap picks the view under it. */
@Composable
private fun Screenshot(
    file: File,
    nodes: List<UiNode>,
    selected: Int?,
    flagged: Set<Int>,
    onSelect: (Int?) -> Unit,
) {
    var picture by remember(file) { mutableStateOf<Pair<Bitmap, Int>?>(null) }
    LaunchedEffect(file) {
        picture =
            withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inSampleSize = SAMPLE }
                BitmapFactory.decodeFile(file.path, options)?.let { it to SAMPLE }
            }
    }
    val (bitmap, sample) = picture ?: return
    val screenWidth = bitmap.width * sample
    val screenHeight = bitmap.height * sample
    val highlight = MaterialTheme.colorScheme.primary
    val warn = MaterialTheme.colorScheme.error
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.height(PREVIEW_DP.dp)
                .aspectRatio(screenWidth.toFloat() / screenHeight)
                .pointerInput(nodes) {
                    detectTapGestures { tap ->
                        val x = (tap.x / size.width * screenWidth).toInt()
                        val y = (tap.y / size.height * screenHeight).toInt()
                        onSelect(UiHierarchy.at(nodes, x, y))
                    }
                }
        ) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "The captured screen",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize()) {
                val scale = size.width / screenWidth
                flagged.forEach { index -> outline(nodes[index].bounds, scale, warn, 1.dp.toPx()) }
                selected?.let { outline(nodes[it].bounds, scale, highlight, 3.dp.toPx()) }
            }
        }
    }
}

private fun DrawScope.outline(bounds: Bounds?, scale: Float, color: Color, width: Float) {
    bounds ?: return
    drawRect(
        color,
        topLeft = Offset(bounds.left * scale, bounds.top * scale),
        size = Size(bounds.width * scale, bounds.height * scale),
        style = Stroke(width),
    )
}

@Composable
private fun Details(node: UiNode, screen: List<UiNode>, issues: List<String>) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    ToolSection(node.label) {
        InfoRow("Class", node.className, copyable = true, mono = true)
        node.resourceId
            .ifBlank { null }
            ?.let { InfoRow("Resource id", it, copyable = true, mono = true) }
        node.text.ifBlank { null }?.let { InfoRow("Text", it, copyable = true) }
        node.contentDescription
            .ifBlank { null }
            ?.let { InfoRow("Description", it, copyable = true) }
        node.bounds?.let { bounds ->
            InfoRow(
                "Bounds",
                "$bounds, ${(bounds.width / density).toInt()} × ${(bounds.height / density).toInt()} dp",
                copyable = true,
                mono = true,
            )
        }
        InfoRow("Package", node.packageName, copyable = true, mono = true)
        InfoRow("Flags", flags(node).ifEmpty { listOf("none") }.joinToString(", "))
        if (issues.isNotEmpty()) {
            Text(
                "Accessibility: " + issues.joinToString(" and "),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        UiHierarchy.selectors(node, screen).forEach { (framework, line) ->
            Column(
                Modifier.fillMaxWidth()
                    .clickable { context.copy("$framework selector", line) }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Text(
                    framework,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        ToolNote("Tap a value or a selector to copy it.")
    }
}

@Composable
private fun NodeRow(node: UiNode, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = (minOf(node.depth, MAX_INDENT) * INDENT_DP).dp,
                top = 4.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                node.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            Text(
                listOfNotNull(
                        node.className.substringAfterLast('.'),
                        node.resourceId.substringAfter(":id/", "").ifEmpty { null },
                        "clickable".takeIf { node.clickable },
                    )
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun flags(node: UiNode): List<String> =
    listOfNotNull(
        "clickable".takeIf { node.clickable },
        "long-clickable".takeIf { node.longClickable },
        "scrollable".takeIf { node.scrollable },
        "disabled".takeUnless { node.enabled },
        "focused".takeIf { node.focused },
        node.checked?.let { if (it) "checked" else "unchecked" },
        "selected".takeIf { node.selected },
        "password".takeIf { node.password },
    )

private const val MIN_TARGET_DP = 48
private const val SAMPLE = 2
private const val PREVIEW_DP = 440
private const val PAGE = 150
private const val MAX_INDENT = 12
private const val INDENT_DP = 8
