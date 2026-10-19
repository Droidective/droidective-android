package com.droidective.android.ui

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidective.android.DroidectiveApplication
import com.droidective.android.droidective
import com.droidective.android.editor.ItemPicker
import com.droidective.android.editor.LayoutEditor
import com.droidective.android.surface.LiveSurfaces
import com.droidective.android.surface.Surfaces
import com.droidective.android.surface.TileSlots
import com.droidective.android.widget.ToggleBarReceiver
import com.droidective.android.widget.ToggleBarWidget
import com.droidective.core.ago
import com.droidective.core.device.AppFreezers
import com.droidective.core.features.Features
import com.droidective.core.layout.Cell
import com.droidective.core.layout.Item
import com.droidective.core.layout.Layout
import com.droidective.core.layout.LayoutKind
import com.droidective.core.layout.LayoutRepository
import java.util.UUID
import kotlinx.coroutines.launch

/** Every place toggles live: home-screen bars, notification bars, folders and tiles. */
@Composable
fun WidgetsScreen(app: DroidectiveApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var slots by remember { mutableStateOf(TileSlots.all(context)) }
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            scope.launch { Surfaces.refresh(context) }
        }

    editing?.let { id ->
        LayoutEditor(id) { editing = null }
        return
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

    LazyColumn(
        Modifier.fillMaxSize()
            .windowInsetsPadding(ScaffoldDefaults.contentWindowInsets)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Widgets",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item { LiveSurfacesRow() }

        item { SectionHeader("Home-screen bars") }
        items(layouts.filter { it.kind == LayoutKind.Bar }, key = { it.id }) { layout ->
            LayoutCard(
                layout,
                onEdit = { editing = layout.id },
                onDelete = { delete(app.layouts, layout, scope, context) },
            )
        }
        item {
            OutlinedButton(onClick = { pinBar(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Add a bar to the home screen")
            }
        }

        item { SectionHeader("Notification bars") }
        items(layouts.filter { it.kind == LayoutKind.Notification }, key = { it.id }) { layout ->
            LayoutCard(
                layout,
                onEdit = { editing = layout.id },
                onDelete = { delete(app.layouts, layout, scope, context) },
            )
        }
        item {
            OutlinedButton(
                onClick = {
                    if (needsNotificationPermission(context)) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    create(
                        LayoutKind.Notification,
                        "Notification bar",
                        ToggleBarWidget.starterToggles,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("New notification bar")
            }
        }

        item { SectionHeader("Folders") }
        items(layouts.filter { it.kind == LayoutKind.Folder }, key = { it.id }) { layout ->
            LayoutCard(
                layout,
                onEdit = { editing = layout.id },
                onDelete = { delete(app.layouts, layout, scope, context) },
            )
        }
        item {
            OutlinedButton(
                onClick = { create(LayoutKind.Folder, "Folder", emptyList()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("New folder")
            }
        }

        item { SectionHeader("Quick Settings tiles") }
        items((1..TileSlots.COUNT).toList(), key = { "slot-$it" }) { slot ->
            TileSlotRow(
                context,
                slot,
                slots[slot],
                onChoose = { pickingSlot = slot },
                onClear = {
                    TileSlots.bind(context, slot, null)
                    slots = TileSlots.all(context)
                },
            )
        }
        item { Text(" ") }
    }

    pickingSlot?.let { slot ->
        ItemPicker(
            kind = LayoutKind.Bar,
            onPick = { item ->
                TileSlots.bind(context, slot, item)
                slots = TileSlots.all(context)
                pickingSlot = null
            },
            onDismiss = { pickingSlot = null },
        )
    }
}

/** The switch for [LiveSurfaces]: a service that keeps widget taps instant and widgets current. */
@Composable
private fun LiveSurfacesRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(LiveSurfaces.enabled(context)) }
    val holdsTaps = remember { AppFreezers.holdsWidgetTaps(Build.MANUFACTURER) }
    ListItem(
        headlineContent = { Text("Keep widgets live") },
        supportingContent = {
            Text(
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
                }
            )
        },
        trailingContent = {
            Switch(
                checked = on,
                onCheckedChange = {
                    LiveSurfaces.setEnabled(context, it)
                    on = it
                },
            )
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun LayoutCard(layout: Layout, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val cells by produceState(emptyList<Cell>(), layout) { value = Surfaces.cells(context, layout) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(layout.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Last edited ${ago(layout.editedAt, System.currentTimeMillis())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            FlowRow(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(layout.style.background))
                        .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                cells.forEach {
                    CellButton(
                        it,
                        Color(layout.style.iconOn),
                        Color(layout.style.iconOff),
                        false,
                        null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TileSlotRow(
    context: Context,
    slot: Int,
    item: Item?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    val label =
        when (item) {
            null -> "Empty"
            is Item.Toggle ->
                com.droidective.core.toggles.Toggles.byId(item.toggleId)?.title ?: "Removed toggle"
            is Item.App -> item.label
            is Item.Shortcut -> item.label
            is Item.DeepLink -> item.label
            is Item.SettingsScreen -> item.label
            is Item.Folder -> "Folder"
            is Item.Command ->
                context.droidective.commands.get(item.commandId)?.name ?: "Removed command"
            is Item.Automation ->
                context.droidective.automations.get(item.automationId)?.name ?: "Removed automation"
            is Item.Action -> item.label
            is Item.Tool -> Features.byId(item.featureId)?.title ?: "Removed tool"
        }
    ListItem(
        headlineContent = { Text("Droidective $slot") },
        supportingContent = { Text(label) },
        trailingContent = {
            Row {
                if (item != null) TextButton(onClick = onClear) { Text("Clear") }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    TextButton(onClick = { requestTile(context, slot) }) {
                        Text("Add to Quick Settings")
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onChoose),
    )
}

private fun delete(
    repository: LayoutRepository,
    layout: Layout,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
) {
    repository.delete(layout.id)
    scope.launch { Surfaces.refresh(context) }
}

private fun pinBar(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(
            ComponentName(context, ToggleBarReceiver::class.java),
            null,
            null,
        )
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
