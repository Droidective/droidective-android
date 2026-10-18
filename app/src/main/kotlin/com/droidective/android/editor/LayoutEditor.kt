package com.droidective.android.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.droidective.android.droidective
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.CellButton
import com.droidective.android.ui.CellIconView
import com.droidective.android.ui.Symbols
import com.droidective.core.layout.Cell
import com.droidective.core.layout.Layout
import com.droidective.core.layout.LayoutEdits
import com.droidective.core.layout.Style
import kotlinx.coroutines.launch

/** Colours a bar's "on" icons can take: Droidective green, MicroToggles blue, amber, white. */
private val onColours = listOf(0xFF6ECC1F, 0xFF29B6F6, 0xFFF0B552, 0xFFFFFFFF)

/**
 * Edits one layout: its items, their order and its style. Every change is saved and redrawn on
 * every surface at once, so there's no save button to forget.
 */
@Composable
fun LayoutEditor(layoutId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val repository = context.droidective.layouts
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf(repository.get(layoutId)) }
    var cells by remember { mutableStateOf(emptyList<Cell>()) }
    var picking by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val current = layout
    if (current == null) {
        // Deleted elsewhere while open, such as from another window.
        LaunchedEffect(Unit) { onDone() }
        return
    }

    LaunchedEffect(current) { cells = Surfaces.cells(context, current) }

    fun update(next: Layout) {
        layout = next
        repository.save(next)
        scope.launch { Surfaces.refresh(context) }
    }

    fun now() = System.currentTimeMillis()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current.kind.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onDone) { Text("Done") }
            }
        }
        item {
            OutlinedTextField(
                value = current.name,
                onValueChange = { update(current.copy(name = it, editedAt = now())) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Preview(current.style, cells) }
        itemsIndexed(cells, key = { _, cell -> cell.key }) { index, cell ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CellIconView(cell, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(cell.label, modifier = Modifier.weight(1f))
                EditButton("arrow_upward", "Move ${cell.label} up", enabled = index > 0) {
                    update(LayoutEdits.move(current, index, index - 1, now()))
                }
                EditButton(
                    "arrow_downward",
                    "Move ${cell.label} down",
                    enabled = index < cells.lastIndex,
                ) {
                    update(LayoutEdits.move(current, index, index + 1, now()))
                }
                EditButton("close", "Remove ${cell.label}") {
                    update(LayoutEdits.remove(current, cell.key, now()))
                }
            }
        }
        item {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(Symbols.byName.getValue("add").outlined), null)
                Spacer(Modifier.width(8.dp))
                Text("Add")
            }
            refusal?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item { HorizontalDivider() }
        item { StyleControls(current.style) { update(LayoutEdits.restyle(current, it, now())) } }
    }

    if (picking) {
        ItemPicker(
            kind = current.kind,
            onPick = { item ->
                picking = false
                when (val result = LayoutEdits.add(current, item, now())) {
                    is LayoutEdits.Result.Done -> {
                        refusal = null
                        update(result.layout)
                    }
                    is LayoutEdits.Result.Refused -> refusal = result.reason
                }
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Preview(style: Style, cells: List<Cell>) {
    FlowRow(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(style.background))
                .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        cells.forEach { cell ->
            CellButton(
                cell,
                Color(style.iconOn),
                Color(style.iconOff),
                style.labels,
                onClick = null,
            )
        }
        if (cells.isEmpty()) {
            Text(
                "Nothing here yet",
                color = Color(style.iconOff),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun StyleControls(style: Style, onChange: (Style) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show labels", modifier = Modifier.weight(1f))
            Switch(checked = style.labels, onCheckedChange = { onChange(style.copy(labels = it)) })
        }
        Text("Background")
        val alpha = ((style.background ushr 24) and 0xFF) / 255f
        Slider(
            value = alpha,
            onValueChange = { a ->
                val rgb = style.background and 0x00FFFFFF
                onChange(style.copy(background = ((a * 255).toLong() shl 24) or rgb))
            },
        )
        Text("Colour when on")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onColours.forEach { colour ->
                val selected = style.iconOn == colour
                androidx.compose.foundation.layout.Box(
                    Modifier.size(32.dp)
                        .clip(CircleShape)
                        .background(Color(colour))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color =
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { onChange(style.copy(iconOn = colour)) }
                )
            }
        }
    }
}

@Composable
private fun EditButton(
    icon: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painterResource(Symbols.byName.getValue(icon).outlined),
            contentDescription = description,
        )
    }
}
