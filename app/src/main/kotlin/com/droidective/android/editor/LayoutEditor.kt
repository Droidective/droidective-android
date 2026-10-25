package com.droidective.android.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidective.android.droidective
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.AdaptiveColumns
import com.droidective.android.ui.AdaptiveRows
import com.droidective.android.ui.CellIconView
import com.droidective.android.ui.Symbols
import com.droidective.core.layout.Cell
import com.droidective.core.layout.Display
import com.droidective.core.layout.Layout
import com.droidective.core.layout.LayoutEdits
import com.droidective.core.layout.Look
import com.droidective.core.layout.previewStyle
import com.droidective.core.layout.usesWholeStyle
import kotlinx.coroutines.launch

/**
 * Edits one layout: what it holds and how it looks. The preview leads, the arrangement sits right
 * under it, and the slots are a grid to tap; the finer style lives in its own panel, a sheet on a
 * phone and a column beside the rest on a tablet. Every change is saved and redrawn on every
 * surface at once, so there's no save button to forget.
 */
@Composable
fun LayoutEditor(layoutId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val repository = context.droidective.layouts
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf(repository.get(layoutId)) }
    var cells by remember { mutableStateOf(emptyList<Cell>()) }
    var picking by remember { mutableStateOf(false) }
    var styling by rememberSaveable { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var relooking by remember { mutableStateOf<Cell?>(null) }
    BackHandler(onBack = onDone)
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

    fun move(cell: Cell, by: Int) {
        val from = current.items.indexOfFirst { it.key == cell.key }
        update(LayoutEdits.move(current, from, from + by, now()))
    }

    val slots =
        SlotActions(
            first = cells.firstOrNull()?.key,
            last = cells.lastOrNull()?.key,
            onLook = { relooking = it },
            onEarlier = { move(it, -1) },
            onLater = { move(it, 1) },
            onRemove = { update(LayoutEdits.remove(current, it.key, now())) },
        )

    AdaptiveColumns(Modifier.fillMaxSize(), minWidth = PANE_MIN) { columns ->
        val twoPanes = columns > 1
        val main: @Composable (Modifier) -> Unit = { modifier ->
            LazyColumn(
                modifier,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            current.kind.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = onDone) { Text("Done") }
                    }
                }
                item(key = "name") {
                    OutlinedTextField(
                        value = current.name,
                        onValueChange = { update(current.copy(name = it, editedAt = now())) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "preview") { Preview(current.previewStyle(), cells) }
                item(key = "arrangement") {
                    DisplayRow(
                        current,
                        showsStyleButton = !twoPanes,
                        onDisplay = { display ->
                            update(
                                LayoutEdits.restyle(
                                    current,
                                    current.style.copy(display = display),
                                    now(),
                                )
                            )
                        },
                        onStyle = { styling = true },
                    )
                }
                item(key = "slots") { Slots(current, cells, slots, refusal) { picking = true } }
            }
        }
        val style: @Composable () -> Unit = {
            StyleControls(current.kind, current.style) {
                update(LayoutEdits.restyle(current, it, now()))
            }
        }
        if (twoPanes) {
            Row(Modifier.fillMaxSize()) {
                main(Modifier.weight(1.2f).fillMaxHeight())
                VerticalDivider()
                Column(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(styleTitle(current), style = MaterialTheme.typography.titleLarge)
                    style()
                }
            }
        } else {
            main(Modifier.fillMaxSize())
            if (styling) StyleSheet(styleTitle(current), onDismiss = { styling = false }, style)
        }
    }

    relooking?.let { cell ->
        LookDialog(
            cell,
            current.looks[cell.key] ?: Look(),
            onDismiss = { relooking = null },
            onSave = { look ->
                relooking = null
                update(LayoutEdits.relook(current, cell.key, look, now()))
            },
        )
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

/** The panel's name: a home-screen widget has a whole style, the others only colours. */
private fun styleTitle(layout: Layout): String =
    if (layout.kind.usesWholeStyle) "Style" else "Colours"

/** Grid or list for a home-screen widget, and on a phone, the way into the style panel. */
@Composable
private fun DisplayRow(
    layout: Layout,
    showsStyleButton: Boolean,
    onDisplay: (Display) -> Unit,
    onStyle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (layout.kind.usesWholeStyle) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                Display.entries.forEachIndexed { index, display ->
                    SegmentedButton(
                        selected = layout.style.display == display,
                        onClick = { onDisplay(display) },
                        shape = SegmentedButtonDefaults.itemShape(index, Display.entries.size),
                        icon = {
                            Icon(
                                painterResource(Symbols.byName.getValue(display.symbol).outlined),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    ) {
                        Text(display.title)
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showsStyleButton) {
            FilledTonalButton(onClick = onStyle) {
                Icon(
                    painterResource(Symbols.byName.getValue("palette").outlined),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(styleTitle(layout))
            }
        }
    }
}

private val Display.symbol: String
    get() =
        when (this) {
            Display.Grid -> "grid_view"
            Display.List -> "view_list"
        }

/** What a tap on a slot offers. */
private class SlotActions(
    val first: String?,
    val last: String?,
    val onLook: (Cell) -> Unit,
    val onEarlier: (Cell) -> Unit,
    val onLater: (Cell) -> Unit,
    val onRemove: (Cell) -> Unit,
)

/** The slots as a grid of tiles, and one to add another. */
@Composable
private fun Slots(
    layout: Layout,
    cells: List<Cell>,
    actions: SlotActions,
    refusal: String?,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Items",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${layout.items.size} of ${layout.kind.maxItems}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Tap an item to change how it looks, move it or remove it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val tiles: List<Cell?> =
            cells + if (layout.items.size < layout.kind.maxItems) listOf(null) else emptyList()
        AdaptiveColumns(minWidth = SLOT_MIN) { columns ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdaptiveRows(tiles, columns) { cell, modifier ->
                    if (cell == null) AddSlot(modifier, onAdd)
                    else SlotTile(cell, actions, modifier)
                }
            }
        }
        refusal?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SlotTile(cell: Cell, actions: SlotActions, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { menu = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CellIconView(
                    cell,
                    if (cell.active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.size(28.dp),
                )
                Text(
                    cell.label,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            fun choose(action: (Cell) -> Unit) {
                menu = false
                action(cell)
            }
            DropdownMenuItem(text = { Text("Change look") }, onClick = { choose(actions.onLook) })
            if (cell.key != actions.first) {
                DropdownMenuItem(
                    text = { Text("Move earlier") },
                    onClick = { choose(actions.onEarlier) },
                )
            }
            if (cell.key != actions.last) {
                DropdownMenuItem(
                    text = { Text("Move later") },
                    onClick = { choose(actions.onLater) },
                )
            }
            DropdownMenuItem(text = { Text("Remove") }, onClick = { choose(actions.onRemove) })
        }
    }
}

@Composable
private fun AddSlot(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(Symbols.byName.getValue("add").outlined),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text("Add", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** Two panes once each gets this much: the preview and slots, and the style beside them. */
private val PANE_MIN = 420.dp

/** A slot tile: room for an icon and a two-line label. */
private val SLOT_MIN = 84.dp
