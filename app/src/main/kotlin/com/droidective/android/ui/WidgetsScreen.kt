package com.droidective.android.ui

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.droidective.android.DroidectiveApplication
import com.droidective.android.droidective
import com.droidective.android.editor.ItemPicker
import com.droidective.android.editor.LayoutEditor
import com.droidective.android.editor.Preview
import com.droidective.android.surface.LiveSurfaces
import com.droidective.android.surface.Surfaces
import com.droidective.android.surface.TileSlots
import com.droidective.android.widget.NetworkConfigureActivity
import com.droidective.android.widget.NetworkWidgetReceiver
import com.droidective.android.widget.NetworkWidgets
import com.droidective.android.widget.Starter
import com.droidective.android.widget.ToggleBarWidget
import com.droidective.android.widget.WidgetReceiver
import com.droidective.core.device.AppFreezers
import com.droidective.core.features.Features
import com.droidective.core.layout.Cell
import com.droidective.core.layout.Cells
import com.droidective.core.layout.Item
import com.droidective.core.layout.Layout
import com.droidective.core.layout.LayoutKind
import com.droidective.core.layout.LayoutRepository
import com.droidective.core.layout.TileSlotNumbers
import com.droidective.core.layout.previewStyle
import com.droidective.core.toggles.Toggles
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Every place toggles live: home-screen widgets, notification bars, folders and tiles. */
@Composable
fun WidgetsScreen(app: DroidectiveApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var pickingSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Layout?>(null) }
    var slots by remember { mutableStateOf(TileSlots.all(context)) }
    // A tap away from the app reports in a toast or a notification; Android hides both while
    // Droidective's notifications are off.
    var notesShow by remember { mutableStateOf(notificationsShow(context)) }
    LifecycleResumeEffect(Unit) {
        notesShow = notificationsShow(context)
        onPauseOrDispose {}
    }
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notesShow = notificationsShow(context)
            scope.launch { Surfaces.refresh(context) }
        }
    fun allowNotifications() {
        if (needsNotificationPermission(context)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings(context)
        }
    }

    editing?.let { id ->
        // Under the status bar otherwise: the tab's own list pads for it, and this replaces it.
        Box(Modifier.windowInsetsPadding(ScaffoldDefaults.contentWindowInsets)) {
            LayoutEditor(id) { editing = null }
        }
        return
    }

    val networkWidgets by
        produceState(NetworkWidgets.placedIds(context)) {
            Surfaces.changes.collect { value = NetworkWidgets.placedIds(context) }
        }
    val layouts by
        produceState(app.layouts.all()) {
            value = app.layouts.all()
            Surfaces.changes.collect { value = app.layouts.all() }
        }

    fun create(kind: LayoutKind, name: String, starter: List<String>) {
        val layout =
            Layout(
                id = "${kind.name.lowercase()}-${UUID.randomUUID().toString().take(8)}",
                kind = kind,
                name = name,
                items =
                    starter.take(kind.maxItems).map {
                        Item.Toggle(UUID.randomUUID().toString(), it)
                    },
                editedAt = System.currentTimeMillis(),
            )
        app.layouts.save(layout)
        editing = layout.id
        scope.launch { Surfaces.refresh(context) }
    }

    AdaptiveColumns(
        Modifier.fillMaxSize().windowInsetsPadding(ScaffoldDefaults.contentWindowInsets)
    ) { columns ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Widgets",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { creating = true }) {
                        SymbolIcon("add", Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New")
                    }
                }
            }
            val placed = layouts.isNotEmpty() || networkWidgets.isNotEmpty() || slots.isNotEmpty()
            if (!notesShow && placed) {
                item(key = "notifications") { NotificationsOffCard(::allowNotifications) }
            }
            if (layouts.isEmpty()) {
                item(key = "empty") { EmptyWidgets() }
            } else {
                adaptiveItems(
                    layouts.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() })),
                    columns,
                    key = { it.id },
                    gutter = 0.dp,
                    // The preview measures its width with BoxWithConstraints.
                    equalHeights = false,
                ) { layout, modifier ->
                    LayoutCard(
                        layout,
                        modifier,
                        onEdit = { editing = layout.id },
                        onDelete = { deleting = layout },
                    )
                }
            }
            networkWidgets.forEach { widgetId ->
                item(key = "network-$widgetId") { NetworkCardRow(widgetId) }
            }
            item(key = "tiles") {
                TilesSection(
                    slots,
                    onChoose = { pickingSlot = it },
                    onRemove = { slot ->
                        TileSlots.bind(context, slot, null)
                        slots = TileSlots.all(context)
                    },
                )
            }
            item(key = "live") { LiveSurfacesCard() }
        }
    }

    if (creating) {
        NewSheet(
            onDismiss = { creating = false },
            onHomeScreen = { starter ->
                creating = false
                if (needsNotificationPermission(context)) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                pinWidget(context, starter)
            },
            onNetwork = {
                creating = false
                if (needsNotificationPermission(context)) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                pinNetworkWidget(context)
            },
            onNotificationBar = {
                creating = false
                if (needsNotificationPermission(context)) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                create(LayoutKind.Notification, "Notification bar", ToggleBarWidget.starterToggles)
            },
            onFolder = {
                creating = false
                create(LayoutKind.Folder, "Folder", emptyList())
            },
        )
    }

    pickingSlot?.let { slot ->
        ItemPicker(
            kind = LayoutKind.Bar,
            onPick = { item ->
                val isNew = slots[slot] == null
                TileSlots.bind(context, slot, item)
                if (item is Item.Network) NetworkWidgets.watch(context)
                slots = TileSlots.all(context)
                pickingSlot = null
                // A new tile goes straight on to the panel; Android asks before it adds it.
                if (isNew) requestTile(context, slot)
            },
            onDismiss = { pickingSlot = null },
        )
    }

    deleting?.let { layout ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${layout.name}”?") },
            text = { Text(deleteNote(layout.kind)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        delete(app.layouts, layout, scope, context)
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun deleteNote(kind: LayoutKind): String =
    when (kind) {
        LayoutKind.Bar -> "If it’s on the home screen, it goes back to how it started."
        LayoutKind.Notification -> "Its notification goes away."
        LayoutKind.Folder -> "Slots that open it go away too."
    }

@Composable
private fun EmptyWidgets() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Nothing yet. A widget puts toggles, apps and tools a tap away on the home screen, " +
                "in a notification or in a folder. Tap New to make one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }
}

/** One widget, notification bar or folder: what it is, what it holds, and how it looks. */
@Composable
private fun LayoutCard(
    layout: Layout,
    modifier: Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val cells by produceState(emptyList<Cell>(), layout) { value = Surfaces.cells(context, layout) }
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SymbolIcon(layout.kind.symbol, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        layout.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${layout.kind.title} · ${count(layout.items.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        SymbolIcon("more_vert", description = "More for ${layout.name}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menu = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            Preview(layout.previewStyle(), cells)
        }
    }
}

private fun count(items: Int): String =
    when (items) {
        0 -> "empty"
        1 -> "1 item"
        else -> "$items items"
    }

private val LayoutKind.symbol: String
    get() =
        when (this) {
            LayoutKind.Bar -> "widgets"
            LayoutKind.Notification -> "notifications"
            LayoutKind.Folder -> "folder"
        }

/** A Quick Settings tile slot that holds something, or the place to add the next one. */
private sealed interface TileEntry {
    data class Slot(val number: Int, val item: Item) : TileEntry

    data class Add(val number: Int) : TileEntry
}

/** The tiles in use, and one place to add another, instead of a row for every empty slot. */
@Composable
private fun TilesSection(
    slots: Map<Int, Item>,
    onChoose: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val entries =
        slots.entries.sortedBy { it.key }.map { TileEntry.Slot(it.key, it.value) } +
            listOfNotNull(
                TileSlotNumbers.firstFree(slots.keys, TileSlots.COUNT)?.let(TileEntry::Add)
            )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Quick Settings tiles")
        Text(
            "Tiles sit in the panel you pull down from the top of the screen. Pick what one " +
                "does, and Android asks to add it there.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AdaptiveColumns(minWidth = TILE_MIN) { columns ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AdaptiveRows(entries, columns) { entry, modifier ->
                    when (entry) {
                        is TileEntry.Slot -> SlotTile(entry, modifier, onChoose, onRemove)
                        is TileEntry.Add -> AddTile(modifier) { onChoose(entry.number) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotTile(
    entry: TileEntry.Slot,
    modifier: Modifier,
    onChoose: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { menu = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SymbolIcon(tileSymbol(entry.item), tint = MaterialTheme.colorScheme.primary)
                Text(
                    tileLabel(context, entry.item),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Tile ${entry.number}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Change") },
                onClick = {
                    menu = false
                    onChoose(entry.number)
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                DropdownMenuItem(
                    text = { Text("Add to Quick Settings") },
                    onClick = {
                        menu = false
                        requestTile(context, entry.number)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Remove") },
                onClick = {
                    menu = false
                    onRemove(entry.number)
                },
            )
        }
    }
}

@Composable
private fun AddTile(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SymbolIcon("add", tint = MaterialTheme.colorScheme.primary)
            Text("Add a tile", style = MaterialTheme.typography.titleSmall)
        }
    }
}

private fun tileLabel(context: Context, item: Item): String =
    when (item) {
        is Item.Toggle -> Toggles.byId(item.toggleId)?.title ?: "Removed toggle"
        is Item.App -> item.label
        is Item.Shortcut -> item.label
        is Item.DeepLink -> item.label
        is Item.SettingsScreen -> item.label
        is Item.Folder -> context.droidective.layouts.get(item.folderId)?.name ?: "Folder"
        is Item.Command ->
            context.droidective.commands.get(item.commandId)?.name ?: "Removed command"
        is Item.Automation ->
            context.droidective.automations.get(item.automationId)?.name ?: "Removed automation"
        is Item.Action -> item.label
        is Item.Tool -> Features.byId(item.featureId)?.title ?: "Removed tool"
        is Item.Network -> item.shows.title
    }

private fun tileSymbol(item: Item): String =
    when (item) {
        is Item.Toggle -> Toggles.byId(item.toggleId)?.icon ?: "toggle_on"
        is Item.App -> "apps"
        is Item.Shortcut -> "open_in_new"
        is Item.DeepLink -> "link"
        is Item.SettingsScreen -> "settings"
        is Item.Folder -> "folder"
        is Item.Command -> "terminal"
        is Item.Automation -> "bolt"
        is Item.Action -> Cells.actionIcon(item.action)
        is Item.Tool -> Features.byId(item.featureId)?.icon ?: "build"
        is Item.Network -> item.shows.symbol
    }

/** What a new widget starts as, in the words the New sheet uses. */
private val Starter.symbol: String
    get() =
        when (this) {
            Starter.Bar -> "view_column"
            Starter.Button -> "smart_button"
            Starter.List -> "view_list"
        }

private val Starter.note: String
    get() =
        when (this) {
            Starter.Bar -> "A row of icons"
            Starter.Button -> "One big button"
            Starter.List -> "Names and states, in rows"
        }

/** Everything that can be made, each with what it is, so nothing needs guessing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSheet(
    onDismiss: () -> Unit,
    onHomeScreen: (Starter) -> Unit,
    onNetwork: () -> Unit,
    onNotificationBar: () -> Unit,
    onFolder: () -> Unit,
) {
    val context = LocalContext.current
    val canPin = remember { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New", style = MaterialTheme.typography.titleLarge)
            SectionHeader("On the home screen")
            Text(
                if (canPin) {
                    "Pick how it starts. You can change everything about it afterwards."
                } else {
                    "This launcher adds widgets from its own list: touch and hold the home " +
                        "screen, then Widgets, then Droidective."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AdaptiveColumns(minWidth = STARTER_MIN) { columns ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AdaptiveRows(Starter.entries, columns.coerceAtMost(Starter.entries.size)) {
                        starter,
                        modifier ->
                        Choice(
                            symbol = starter.symbol,
                            title = starter.short,
                            note = starter.note,
                            enabled = canPin,
                            modifier = modifier,
                        ) {
                            onHomeScreen(starter)
                        }
                    }
                }
            }
            Choice(
                symbol = "wifi",
                title = "Network",
                note = "The Wi-Fi name and IP address, local and public; a tap copies it",
                enabled = canPin,
                modifier = Modifier.fillMaxWidth(),
                onClick = onNetwork,
            )
            SectionHeader("Elsewhere")
            Choice(
                symbol = "notifications",
                title = "Notification bar",
                note =
                    "Up to ${LayoutKind.Notification.maxItems} items in a notification, one " +
                        "swipe away anywhere",
                modifier = Modifier.fillMaxWidth(),
                onClick = onNotificationBar,
            )
            Choice(
                symbol = "folder",
                title = "Folder",
                note = "A group of items that one slot opens",
                modifier = Modifier.fillMaxWidth(),
                onClick = onFolder,
            )
        }
    }
}

@Composable
private fun Choice(
    symbol: String,
    title: String,
    note: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SymbolIcon(symbol, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The switch for [LiveSurfaces]: a service that keeps widget taps instant and widgets current. */
/** Says why a widget's taps go unanswered, with the way to fix it. */
@Composable
private fun NotificationsOffCard(onAllow: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Taps can’t say how they went",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Notifications are off for Droidective, and Android hides its toasts with " +
                    "them, so a tap on a widget, tile or notification bar shows nothing when " +
                    "it works or when it needs you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onAllow) { Text("Allow notifications") }
        }
    }
}

@Composable
private fun LiveSurfacesCard() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(LiveSurfaces.enabled(context)) }
    val holdsTaps = remember { AppFreezers.holdsWidgetTaps(Build.MANUFACTURER) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        SwitchRow(
            "Keep widgets live",
            checked = on,
            note =
                buildString {
                    append(
                        "Widgets, tiles and notification bars follow changes made anywhere, " +
                            "and a quiet notification stays up while any is placed."
                    )
                    if (holdsTaps) {
                        append(
                            " This phone freezes apps in the background and can hold a " +
                                "widget tap for half a minute; keeping widgets live stops that."
                        )
                    }
                },
            modifier = Modifier.padding(16.dp),
            onChange = {
                LiveSurfaces.setEnabled(context, it)
                on = it
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun SymbolIcon(
    name: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    description: String? = null,
) {
    androidx.compose.material3.Icon(
        painterResource(
            Symbols.byName[name]?.outlined ?: Symbols.byName.getValue("toggle_on").outlined
        ),
        contentDescription = description,
        tint = tint,
        modifier = modifier,
    )
}

private fun delete(
    repository: LayoutRepository,
    layout: Layout,
    scope: CoroutineScope,
    context: Context,
) {
    repository.delete(layout.id)
    scope.launch { Surfaces.refresh(context) }
}

/** Asks the launcher to place a network widget; it shows its own confirmation. */
private fun pinNetworkWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(
            ComponentName(context, NetworkWidgetReceiver::class.java),
            null,
            null,
        )
    }
}

/** A placed network widget: what it shows, and a tap to change it. */
@Composable
private fun NetworkCardRow(widgetId: Int) {
    val context = LocalContext.current
    val card = remember(widgetId) { NetworkWidgets.card(context, widgetId) }
    val shows =
        listOfNotNull(
                "name".takeIf { card.showName },
                "local IP".takeIf { card.showLocal },
                "public IP".takeIf { card.showPublic },
            )
            .joinToString(", ")
            .replaceFirstChar { it.uppercase() }
    Surface(
        onClick = {
            context.startActivity(
                Intent(context, NetworkConfigureActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            )
        },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolIcon("wifi", tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Network", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Home-screen widget · ${shows.ifEmpty { "Local IP" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SymbolIcon("chevron_right")
        }
    }
}

/** Asks the launcher to place a widget of [starter]'s kind; it shows its own confirmation. */
private fun pinWidget(context: Context, starter: Starter) {
    val manager = AppWidgetManager.getInstance(context)
    val receiver = WidgetReceiver.kinds.entries.first { it.value == starter }.key
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
    }
}

/** Android 13's one-tap prompt to place a tile; the app has to be in the foreground to ask. */
private fun requestTile(context: Context, slot: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val service = TileSlots.services[slot - 1]
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, service),
        "Droidective $slot",
        Icon.createWithResource(context, Symbols.byName.getValue("toggle_on").outlined),
        context.mainExecutor,
    ) {}
}

private val TILE_MIN = 150.dp
private val STARTER_MIN = 100.dp
