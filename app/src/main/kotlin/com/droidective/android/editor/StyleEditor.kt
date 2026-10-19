package com.droidective.android.editor

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidective.android.ui.CellIconView
import com.droidective.android.ui.LocalLevelColors
import com.droidective.android.ui.SwitchRow
import com.droidective.android.ui.Symbols
import com.droidective.core.layout.Cell
import com.droidective.core.layout.CellShape
import com.droidective.core.layout.Display
import com.droidective.core.layout.Look
import com.droidective.core.layout.Style
import com.droidective.core.layout.StylePreset
import com.droidective.core.layout.WidgetGrid

/** Colours an icon or a label can take. */
private val iconColours =
    listOf(
        0xFF6ECC1F,
        0xFF29B6F6,
        0xFF5C6BC0,
        0xFFAB47BC,
        0xFFEC407A,
        0xFFEF5350,
        0xFFFFA726,
        0xFFF0B552,
        0xFFFFEE58,
        0xFF26A69A,
        0xFF26C6DA,
        0xFFFFFFFF,
        0xFFBDBDBD,
        0xFF8D948F,
        0xFF424242,
        0xFF000000,
    )

/** Background colours, without their opacity, which has its own slider. */
private val backgroundColours =
    listOf(
        0xFF212322,
        0xFF000000,
        0xFF1E1E2E,
        0xFF263238,
        0xFF1A237E,
        0xFF004D40,
        0xFF3E2723,
        0xFF4A148C,
        0xFFF1F3F4,
        0xFFFFFFFF,
    )

/** Everything about how a layout draws, with the preview above it showing each change. */
@Composable
internal fun StyleControls(style: Style, onChange: (Style) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Colours")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StylePreset.entries
                .filter {
                    it != StylePreset.Wallpaper || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                }
                .forEach { preset ->
                    FilterChip(
                        selected = preset.isOn(style),
                        onClick = { onChange(preset.applyTo(style)) },
                        label = { Text(preset.title) },
                    )
                }
        }
        if (!style.dynamicColors) {
            Text("Background")
            Swatches(backgroundColours, style.background or ALPHA_MASK) { rgb ->
                onChange(
                    style.copy(background = (style.background and ALPHA_MASK) or (rgb and RGB_MASK))
                )
            }
            LabeledSlider("Opacity", "${(alphaOf(style.background) * 100).toInt()}%") {
                Slider(
                    value = alphaOf(style.background),
                    onValueChange = { a ->
                        onChange(style.copy(background = withAlpha(style.background, a)))
                    },
                )
            }
            Text("Colour when on")
            Swatches(iconColours, style.iconOn) { onChange(style.copy(iconOn = it)) }
            Text("Colour when off")
            Swatches(iconColours, style.iconOff) { onChange(style.copy(iconOff = it)) }
        }

        Section("Arrangement")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Display.entries.forEachIndexed { index, display ->
                SegmentedButton(
                    selected = style.display == display,
                    onClick = { onChange(style.copy(display = display)) },
                    shape = SegmentedButtonDefaults.itemShape(index, Display.entries.size),
                ) {
                    Text(display.title)
                }
            }
        }
        if (style.display == Display.Grid) {
            LabeledSlider(
                "Columns",
                if (style.columns == 0) "As many as fit" else "${style.columns}",
            ) {
                Slider(
                    value = style.columns.toFloat(),
                    onValueChange = { onChange(style.copy(columns = it.toInt())) },
                    valueRange = 0f..Style.MAX_COLUMNS.toFloat(),
                    steps = Style.MAX_COLUMNS - 1,
                )
            }
        }
        IntSlider("Icon size", style.iconSize, Style.ICON_SIZES, "dp") {
            onChange(style.copy(iconSize = it))
        }
        IntSlider("Corners", style.cornerRadius, Style.CORNERS, "dp") {
            onChange(style.copy(cornerRadius = it))
        }
        IntSlider("Edge padding", style.padding, Style.PADDINGS, "dp") {
            onChange(style.copy(padding = it))
        }
        Text("Behind each icon")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CellShape.entries.forEachIndexed { index, shape ->
                SegmentedButton(
                    selected = style.cellShape == shape,
                    onClick = { onChange(style.copy(cellShape = shape)) },
                    shape = SegmentedButtonDefaults.itemShape(index, CellShape.entries.size),
                ) {
                    Text(shape.title)
                }
            }
        }

        Section("Text")
        SwitchRow("Show labels", style.labels) { onChange(style.copy(labels = it)) }
        SwitchRow("Show each slot's state", style.stateText) {
            onChange(style.copy(stateText = it))
        }
        IntSlider("Text size", style.labelSize, Style.LABEL_SIZES, "sp") {
            onChange(style.copy(labelSize = it))
        }
        if (!style.dynamicColors) {
            Text("Text colour")
            Swatches(iconColours, style.labelColor ?: style.iconOff) {
                onChange(style.copy(labelColor = it))
            }
        }
    }
}

/** The widget as it will draw: the same columns, sizes, chips and colours. */
@Composable
internal fun Preview(style: Style, cells: List<Cell>) {
    val background =
        if (style.dynamicColors) MaterialTheme.colorScheme.surfaceContainerHigh
        else Color(style.background)
    val on = if (style.dynamicColors) MaterialTheme.colorScheme.primary else Color(style.iconOn)
    val off =
        if (style.dynamicColors) MaterialTheme.colorScheme.onSurfaceVariant
        else Color(style.iconOff)
    val label =
        if (style.dynamicColors) MaterialTheme.colorScheme.onSurface
        else Color(style.labelColor ?: style.iconOff)
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(style.cornerRadius.dp))
            .background(background)
            .padding(style.padding.dp)
    ) {
        if (cells.isEmpty()) {
            Text("Nothing here yet", color = off, modifier = Modifier.padding(16.dp))
            return@BoxWithConstraints
        }
        if (style.display == Display.List) {
            Column {
                cells.forEach { cell ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PreviewIcon(cell, style, on, off)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(cell.label, color = label, fontSize = (style.labelSize + 3).sp)
                            if (style.stateText && cell.state != null) {
                                Text(
                                    cell.state.orEmpty(),
                                    color = tint(cell, on, off),
                                    fontSize = style.labelSize.sp,
                                )
                            }
                        }
                    }
                }
            }
            return@BoxWithConstraints
        }
        val columns = WidgetGrid.columns(maxWidth.value, cells.size, style)
        Column {
            cells.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { cell ->
                        Column(
                            Modifier.weight(1f).padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PreviewIcon(cell, style, on, off)
                            if (style.labels) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    cell.label,
                                    color = label,
                                    fontSize = style.labelSize.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (style.stateText && cell.state != null) {
                                Text(
                                    cell.state.orEmpty(),
                                    color = tint(cell, on, off),
                                    fontSize = (style.labelSize - 1).sp,
                                )
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun tint(cell: Cell, on: Color, off: Color): Color {
    val own = cell.color?.takeIf { cell.active || cell.state == null }
    return when {
        own != null -> Color(own)
        cell.active -> on
        else -> off
    }
}

@Composable
private fun PreviewIcon(cell: Cell, style: Style, on: Color, off: Color) {
    val tint = tint(cell, on, off)
    Box(contentAlignment = Alignment.TopEnd) {
        if (style.cellShape == CellShape.None) {
            CellIconView(cell, tint, Modifier.size(style.iconSize.dp))
        } else {
            val chip = (style.iconSize * CHIP_SCALE).dp
            val shape =
                if (style.cellShape == CellShape.Circle) CircleShape
                else RoundedCornerShape(chip / 4)
            Box(
                Modifier.size(chip)
                    .clip(shape)
                    .background(
                        if (cell.active) on.copy(alpha = 0.25f) else off.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CellIconView(cell, tint, Modifier.size(style.iconSize.dp))
            }
        }
        if (cell.degraded) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(LocalLevelColors.current.shell))
        }
    }
}

/** A slot's own label, symbol and colour. Leaving one empty keeps the slot's default. */
@Composable
internal fun LookDialog(cell: Cell, look: Look, onDismiss: () -> Unit, onSave: (Look) -> Unit) {
    var label by remember { mutableStateOf(look.label.orEmpty()) }
    var icon by remember { mutableStateOf(look.icon) }
    var color by remember { mutableStateOf(look.color) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cell.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("Label") },
                    placeholder = { Text(cell.label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Colour")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { color = null }) { Text("Default") }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (color == null) "the bar's" else "its own",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Swatches(iconColours, color ?: 0) { color = it }
                Text("Icon")
                OutlinedTextField(
                    query,
                    { query = it },
                    placeholder = { Text("Search icons") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val names =
                    Symbols.byName.keys
                        .filter { it.contains(query.trim().replace(' ', '_')) }
                        .sorted()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(44.dp),
                    modifier = Modifier.heightIn(max = 220.dp),
                ) {
                    items(names, key = { it }) { name ->
                        val selected = icon == name
                        Box(
                            Modifier.padding(2.dp)
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { icon = if (selected) null else name },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(Symbols.byName.getValue(name).outlined),
                                contentDescription = name.replace('_', ' '),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(Look(label.trim().ifEmpty { null }, icon, color)) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(Look()) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LabeledSlider(label: String, value: String, slider: @Composable () -> Unit) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        slider()
    }
}

@Composable
private fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    unit: String,
    onChange: (Int) -> Unit,
) {
    LabeledSlider(label, "$value $unit") {
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
    }
}

/** Round swatches; the one matching [selected] (ignoring opacity) is ringed. */
@Composable
private fun Swatches(colours: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        colours.forEach { colour ->
            val isSelected = (selected and RGB_MASK) == (colour and RGB_MASK) && selected != 0L
            Box(
                Modifier.size(32.dp)
                    .clip(CircleShape)
                    .background(Color(colour))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color =
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onPick(colour) }
            )
        }
    }
}

private fun alphaOf(argb: Long): Float = ((argb ushr 24) and 0xFF) / 255f

private fun withAlpha(argb: Long, alpha: Float): Long =
    ((alpha * 255).toLong() shl 24) or (argb and RGB_MASK)

private const val RGB_MASK = 0x00FFFFFFL
private const val ALPHA_MASK = 0xFF000000L
private const val CHIP_SCALE = 1.6f
