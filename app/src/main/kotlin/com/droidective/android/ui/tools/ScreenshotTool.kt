package com.droidective.android.ui.tools

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.screenshot.MarkPainter
import com.droidective.android.screenshot.MarkupExport
import com.droidective.android.screenshot.Screenshots
import com.droidective.android.screenshot.Shot
import com.droidective.core.screenshot.Mark
import com.droidective.core.screenshot.Markup
import com.droidective.core.screenshot.Spot
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A screenshot to mark up: counted down, so you can open what to capture, then taken through the
 * shell, and opened to draw on — arrows, boxes, text, and what to hide — before it's saved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreenshotTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val shot by Screenshots.state.collectAsStateWithLifecycle()
    (shot as? Shot.Taken)?.let { taken ->
        MarkupEditor(taken.picture, tool) { Screenshots.done() }
        return
    }
    var delay by rememberSaveable { mutableIntStateOf(DELAYS.first()) }
    ToolScaffold("Screenshot", tool) {
        if (access.shell == null) {
            ToolNote("screencap runs as the shell user.")
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        ToolNote(
            "Counts down so you can open what to capture, then takes it and opens it here to " +
                "mark up. Nothing is saved until you save it."
        )
        when (val now = shot) {
            is Shot.Counting -> Text("Taking it in ${now.left} s")
            Shot.Taking -> Text("Taking it…")
            is Shot.Failed -> Text(now.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        Text("Wait for", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DELAYS.forEach { seconds ->
                FilterChip(
                    selected = delay == seconds,
                    onClick = { delay = seconds },
                    label = { Text("$seconds s") },
                )
            }
        }
        Button(onClick = { Screenshots.take(context, delay) }, enabled = !Screenshots.busy) {
            Text("Take a screenshot")
        }
    }
}

/** What a drag or a tap on the picture draws. */
private enum class Pen(val title: String) {
    Pen("Pen"),
    Highlight("Highlight"),
    Arrow("Arrow"),
    Box("Box"),
    Text("Text"),
    Black("Black out"),
    Pixels("Pixelate"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkupEditor(picture: File, tool: ToolContext, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap by
        produceState<Bitmap?>(null, picture) {
            value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(picture.path) }
        }
    var markup by rememberSaveable(picture.path, stateSaver = MARKUP) { mutableStateOf(Markup()) }
    var pen by rememberSaveable { mutableStateOf(Pen.Arrow) }
    var color by rememberSaveable { mutableStateOf(COLORS.first()) }
    var labelAt by remember { mutableStateOf<Spot?>(null) }
    var saving by remember { mutableStateOf(false) }
    // What was drawn since the last save: leaving with any of it asks first.
    var savedAs by rememberSaveable(picture.path, stateSaver = MARKUP) { mutableStateOf(Markup()) }
    var leaving by remember { mutableStateOf(false) }
    val leave = { if (markup.marks == savedAs.marks) onDone() else leaving = true }
    ToolScaffold("Mark up", ToolContext(tool.snackbar, onBack = leave, tool.onOpenSetup)) {
        val shown = bitmap
        if (shown == null) {
            ToolNote("Opening the screenshot…")
            return@ToolScaffold
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pen.entries.forEach { choice ->
                FilterChip(
                    selected = pen == choice,
                    onClick = { pen = choice },
                    label = { Text(choice.title) },
                )
            }
        }
        // Always there, so the picture doesn't move under a finger when the pen changes.
        Colors(color) { color = it }
        MarkingArea(shown, markup.marks, pen, color, onMark = { markup = markup.adding(it) }) {
            labelAt = it
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { markup = markup.undo() }, enabled = markup.canUndo) {
                Text("Undo")
            }
            TextButton(onClick = { markup = markup.redo() }, enabled = markup.canRedo) {
                Text("Redo")
            }
            TextButton(onClick = { markup = markup.clear() }, enabled = markup.marks.isNotEmpty()) {
                Text("Clear")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val saved =
                            try {
                                MarkupExport.save(context, shown, markup.marks)
                            } catch (e: IOException) {
                                tool.say("It couldn't be saved: ${e.message}")
                                null
                            }
                        saving = false
                        if (saved == null) return@launch
                        savedAs = markup
                        tool.say("Saved to Pictures/Droidective")
                        try {
                            context.startActivity(MarkupExport.share(saved))
                        } catch (_: ActivityNotFoundException) {
                            // Saved is what was asked; sharing it was an offer.
                        }
                    }
                },
                enabled = !saving,
            ) {
                Text(if (saving) "Saving…" else "Save and share")
            }
            OutlinedButton(onClick = leave) { Text("Take another") }
        }
        ToolNote(
            "Pixelate turns what's under it to blocks too coarse to read; black out paints it " +
                "over. Colours are for the pen, highlight, arrow, box and text."
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave without saving?") },
            text = { Text("What you drew on this screenshot isn't saved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        leaving = false
                        onDone()
                    }
                ) {
                    Text("Leave")
                }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Keep editing") } },
        )
    }
    labelAt?.let { at ->
        LabelDialog(
            onDismiss = { labelAt = null },
            onAdd = { text ->
                markup = markup.adding(Mark.Label(at, text, color, LABEL_SIZE))
                labelAt = null
            },
        )
    }
}

@Composable
private fun Colors(chosen: Long, onPick: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        COLORS.forEach { color ->
            Box(
                Modifier.size(SWATCH)
                    .background(Color(color), CircleShape)
                    .border(
                        if (color == chosen) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        CircleShape,
                    )
                    .clickable { onPick(color) }
            )
        }
    }
}

/**
 * The picture, fitted to the width and to most of the screen's height, with the marks over it. A
 * drag draws with [pen]; with the text pen, a tap asks what to write there.
 */
@Composable
private fun MarkingArea(
    picture: Bitmap,
    marks: List<Mark>,
    pen: Pen,
    color: Long,
    onMark: (Mark) -> Unit,
    onLabel: (Spot) -> Unit,
) {
    val painter = remember(picture) { MarkPainter(picture) }
    val window = LocalWindowInfo.current.containerSize
    val tallest = with(LocalDensity.current) { window.height.toDp() } * FITS
    var draft by remember { mutableStateOf<Mark?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val (wide, tall) = fitted(picture, maxWidth, tallest)
        val density = LocalDensity.current
        val pixels = with(density) { Size(wide.toPx(), tall.toPx()) }
        fun spot(at: Offset) =
            Spot((at.x / pixels.width).coerceIn(0f, 1f), (at.y / pixels.height).coerceIn(0f, 1f))
        Box(Modifier.size(wide, tall)) {
            Image(
                picture.asImageBitmap(),
                contentDescription = "The screenshot",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.size(wide, tall),
            )
            Canvas(
                Modifier.size(wide, tall).pointerInput(pen, color) {
                    if (pen == Pen.Text) {
                        detectTapGestures { onLabel(spot(it)) }
                        return@pointerInput
                    }
                    detectDragGestures(
                        onDragStart = { draft = start(pen, color, spot(it)) },
                        onDragEnd = {
                            draft?.let(onMark)
                            draft = null
                        },
                        onDragCancel = { draft = null },
                    ) { change, _ ->
                        change.consume()
                        draft = draft?.let { moved(it, spot(change.position)) }
                    }
                }
            ) {
                drawIntoCanvas { canvas ->
                    painter.draw(canvas.nativeCanvas, size.width, size.height, marks, draft)
                }
            }
        }
    }
}

/** The mark a drag starting at [at] makes. */
private fun start(pen: Pen, color: Long, at: Spot): Mark =
    when (pen) {
        Pen.Pen -> Mark.Line(listOf(at), color, LINE)
        Pen.Highlight ->
            Mark.Line(listOf(at), (color and 0xFFFFFF) or HIGHLIGHT_ALPHA, WIDE, highlight = true)
        Pen.Arrow -> Mark.Arrow(at, at, color, LINE)
        Pen.Box -> Mark.Box(at, at, color, LINE)
        Pen.Black -> Mark.Redact(at, at, Mark.Redact.Style.Solid)
        Pen.Pixels -> Mark.Redact(at, at, Mark.Redact.Style.Pixelate)
        Pen.Text -> Mark.Label(at, "", color, LABEL_SIZE)
    }

/** [mark] as the drag reaches [to]: a line goes on, a shape stretches to the finger. */
private fun moved(mark: Mark, to: Spot): Mark =
    when (mark) {
        is Mark.Line -> mark.adding(to)
        is Mark.Arrow -> mark.copy(to = to)
        is Mark.Box -> mark.copy(to = to)
        is Mark.Redact -> mark.copy(to = to)
        is Mark.Label -> mark
    }

@Composable
private fun LabelDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text.trim()) }, enabled = text.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The size [picture] is shown at: as wide as there is room for, no taller than [tallest]. */
private fun fitted(picture: Bitmap, width: Dp, tallest: Dp): Pair<Dp, Dp> {
    val aspect = picture.width.toFloat() / picture.height
    val byWidth = width / aspect
    return if (byWidth <= tallest) width to byWidth else tallest * aspect to tallest
}

private val MARKUP = Saver<Markup, String>(save = { it.encode() }, restore = { Markup.decode(it) })

private val DELAYS = listOf(3, 5, 10)

/** Red, yellow, green, blue, black, white: what a bug report needs, and nothing it doesn't. */
private val COLORS = listOf(0xFFE53935, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF000000, 0xFFFFFFFF)

private const val LINE = 0.008f
private const val WIDE = 0.035f
private const val LABEL_SIZE = 0.05f
private const val HIGHLIGHT_ALPHA = 0x66000000L
private const val FITS = 0.62f
private val SWATCH = 28.dp
